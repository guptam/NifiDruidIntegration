/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hortonworks.nifi.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.InitializationException;
import org.codehaus.jackson.map.ObjectMapper;
import org.joda.time.DateTime;
import org.joda.time.Period;

import com.hortonworks.nifi.controller.api.DruidTranquilityService;
import com.metamx.common.Granularity;
import com.metamx.tranquility.beam.Beam;
import com.metamx.tranquility.beam.ClusteredBeamTuning;
import com.metamx.tranquility.druid.DruidBeamConfig;
import com.metamx.tranquility.druid.DruidBeams;
import com.metamx.tranquility.druid.DruidDimensions;
import com.metamx.tranquility.druid.DruidEnvironment;
import com.metamx.tranquility.druid.DruidLocation;
import com.metamx.tranquility.druid.DruidRollup;
import com.metamx.tranquility.tranquilizer.Tranquilizer;
import com.metamx.tranquility.typeclass.Timestamper;

import io.druid.data.input.impl.TimestampSpec;
import io.druid.granularity.QueryGranularity;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.CountAggregatorFactory;
import io.druid.query.aggregation.DoubleMaxAggregatorFactory;
import io.druid.query.aggregation.DoubleMinAggregatorFactory;
import io.druid.query.aggregation.DoubleSumAggregatorFactory;
import io.druid.query.aggregation.LongMaxAggregatorFactory;
import io.druid.query.aggregation.LongMinAggregatorFactory;
import io.druid.query.aggregation.LongSumAggregatorFactory;

@Tags({"Druid","Timeseries","OLAP"})
@CapabilityDescription("Asyncronously sends flowfiles to Druid Indexing Task using Tranquility API. "
		+ "If aggregation and roll-up of data is required, an Aggregator JSON desriptor needs to be provided."
		+ "Details on how desribe aggregation using JSON can be found at: http://druid.io/docs/latest/querying/aggregations.html")
public class DruidTranquilityController extends AbstractControllerService implements DruidTranquilityService{
	private String firehosePattern = "druid:firehose:%s";
	private int clusterPartitions = 1;
    private int clusterReplication = 1 ;
    private String indexRetryPeriod = "PT10M";
    
    private Tranquilizer tranquilizer = null;
    
	public static final PropertyDescriptor DATASOURCE = new PropertyDescriptor.Builder()
            .name("data_source")
            .description("Druid Data Source")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
	
	public static final PropertyDescriptor CONNECT_STRING = new PropertyDescriptor.Builder()
            .name("zk_connect_string")
            .description("ZK Connect String for Druid ")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
	
	public static final PropertyDescriptor DRUID_INDEX_SERVICE_PATH = new PropertyDescriptor.Builder()
            .name("index_service_path")
            .description("Druid Index Service path as defined via the Druid Overlord druid.service property.")
            .required(true)
            .defaultValue("druid/overlord")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
	
	public static final PropertyDescriptor DRUID_DISCOVERY_PATH = new PropertyDescriptor.Builder()
            .name("discovery_path")
            .description("Druid Discovery Path as configured in Druid Common druid.discovery.curator.path property")
            .required(true)
            .defaultValue("/druid/discovery")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
	
	public static final PropertyDescriptor TIMESTAMP_FIELD = new PropertyDescriptor.Builder()
            .name("timestamp_field")
            .description("The name of the field that will be used as the timestamp. Should be in ISO format.")
            .required(true)
            //.allowableValues("json", "xml")
            .defaultValue("timestamp")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
	
	public static final PropertyDescriptor AGGREGATOR_JSON = new PropertyDescriptor.Builder()
            .name("aggregators_descriptor")
            .description("Tranquility compliant JSON string that defines what aggregators to apply on ingest."
            		+ "Example: "
            		+ "["
            		+ 	"{"
            		+ 	"	\"type\" : \"count\","
            		+ 	"	\"name\" : \"count\","
            		+ 	"},"
            		+	"{"
            		+ 	"	\"name\" : \"value_sum\","
            		+ 	"	\"type\" : \"doubleSum\","
            		+ 	"	\"fieldName\" : \"value\""
            		+	"},"
            		+	"{"
            		+	"	\"fieldName\" : \"value\","
            		+	"	\"name\" : \"value_min\","
            		+ 	"	\"type\" : \"doubleMin\""
            		+	"},"
            		+ 	"{"
            		+ 	"	\"type\" : \"doubleMax\","
            		+ 	"	\"name\" : \"value_max\","
            		+ 	"	\"fieldName\" : \"value\""
            		+ 	"}"
            		+ "]")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
	
	public static final PropertyDescriptor DIMENSIONS_LIST = new PropertyDescriptor.Builder()
            .name("dimensions_list")
            .description("A comma separated list of field names that will be stored as dimensions on ingest.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
	
	public static final PropertyDescriptor SEGMENT_GRANULARITY = new PropertyDescriptor.Builder()
            .name("segment_granularity")
            .description("Time unit by which to group and aggregate/rollup events.")
            .required(true)
            .allowableValues("NONE","SECOND","MINUTE","TEN_MINUTE","HOUR","DAY","MONTH","YEAR")
            .defaultValue("MINUTE")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
	
	public static final PropertyDescriptor QUERY_GRANULARITY = new PropertyDescriptor.Builder()
            .name("query_granularity")
            .description("Time unit by which to group and aggregate/rollup events.")
            .required(true)
            .allowableValues("NONE","SECOND","MINUTE","TEN_MINUTE","HOUR","DAY","MONTH","YEAR")
            .defaultValue("TEN_MINUTE")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
	
	public static final PropertyDescriptor WINDOW_PERIOD = new PropertyDescriptor.Builder()
            .name("window_period")
            .description("Grace period to allow late arriving events for real time ingest.")
            .required(true)
            .allowableValues("PT1M","PT10M","PT60M")
            .defaultValue("PT10M")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

	 private static final List<PropertyDescriptor> properties;
	
	static{
		final List<PropertyDescriptor> props = new ArrayList<>();
	    props.add(DATASOURCE);
	    props.add(CONNECT_STRING);
	    props.add(DRUID_INDEX_SERVICE_PATH);
	    props.add(DRUID_DISCOVERY_PATH);
	    props.add(DIMENSIONS_LIST);
	    props.add(AGGREGATOR_JSON);
	    props.add(SEGMENT_GRANULARITY);
	    props.add(QUERY_GRANULARITY);
	    props.add(WINDOW_PERIOD);
	    props.add(TIMESTAMP_FIELD);
	    
	    properties = Collections.unmodifiableList(props);
	}
	
	@Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

	@OnEnabled
	public void onConfigured(final ConfigurationContext context) throws InitializationException{
		getLogger().info("Starting Druid Tranquility Controller Service...");
	   
		final String dataSource = context.getProperty(DATASOURCE).getValue();
		final String zkConnectString = context.getProperty(CONNECT_STRING).getValue();
		final String indexService = context.getProperty(DRUID_INDEX_SERVICE_PATH).getValue();
		final String discoveryPath = context.getProperty(DRUID_DISCOVERY_PATH).getValue();
		final String timestampField  = context.getProperty(TIMESTAMP_FIELD).getValue();
		final String segmentGranularity = context.getProperty(SEGMENT_GRANULARITY).getValue();
		final String queryGranularity = context.getProperty(QUERY_GRANULARITY).getValue();
		final String windowPeriod = context.getProperty(WINDOW_PERIOD).getValue();
		final String aggregatorJSON = context.getProperty(AGGREGATOR_JSON).getValue();
		final String dimensionsStringList = context.getProperty(DIMENSIONS_LIST).getValue();
		
		final List<String> dimensions = getDimensions(dimensionsStringList);
	    final List<AggregatorFactory> aggregator = getAggregatorList(aggregatorJSON);
	    
		final Timestamper<Map<String, Object>> timestamper = new Timestamper<Map<String, Object>>(){
			private static final long serialVersionUID = 1L;

			@Override
			public DateTime timestamp(Map<String, Object> theMap){
				return new DateTime(theMap.get(timestampField));
			}
		};
		
		Iterator<AggregatorFactory> aggIterator = aggregator.iterator();
		AggregatorFactory currFactory;
		getLogger().debug("Number of Aggregations Defined: " + aggregator.size());
		while(aggIterator.hasNext()){
			currFactory = aggIterator.next();
			getLogger().debug("Verifying Aggregator Definition");
			getLogger().debug("Aggregator Name: " + currFactory.getName());
			getLogger().debug("Aggregator Type: " + currFactory.getTypeName());
			getLogger().debug("Aggregator Req Fields: " + currFactory.requiredFields());			
		}
		// Tranquility uses ZooKeeper (through Curator) for coordination.
		final CuratorFramework curator = CuratorFrameworkFactory
		                .builder()
		                .connectString(zkConnectString)
		                .retryPolicy(new ExponentialBackoffRetry(1000, 20, 30000))
		                .build();
		curator.start();

		// The JSON serialization of your object must have a timestamp field in a format that Druid understands. By default,
		// Druid expects the field to be called "timestamp" and to be an ISO8601 timestamp.
		final TimestampSpec timestampSpec = new TimestampSpec(timestampField, "auto", null);
		        
		final Beam<Map<String, Object>> beam = DruidBeams.builder(timestamper)
			    		.curator(curator)
		                .discoveryPath(discoveryPath)
		                .location(DruidLocation.create(DruidEnvironment.create(indexService, firehosePattern),dataSource))
		                .timestampSpec(timestampSpec)
		                .rollup(DruidRollup.create(DruidDimensions.specific(dimensions), aggregator, QueryGranularity.fromString(queryGranularity)))
		                .tuning(
		                        ClusteredBeamTuning
		                                .builder()
		                                //.segmentGranularity(Granularity.MINUTE)
		                                .segmentGranularity(getSegmentGranularity(segmentGranularity))
		                                .windowPeriod(new Period(windowPeriod))
		                                .partitions(clusterPartitions)
		                                .replicants(clusterReplication)
		                                .build()
		                )
		                .druidBeamConfig(
		                        DruidBeamConfig
		                                .builder()
		                                .indexRetryPeriod(new Period(indexRetryPeriod))
		                                .build())
		                .buildBeam();
		
		tranquilizer = Tranquilizer.builder()
                .maxBatchSize(10000000)
                .maxPendingBatches(1000)
                .lingerMillis(1000)
                .blockOnFull(true)
                .build(beam);
		
        tranquilizer.start();
	}
	
	public Tranquilizer getTranquilizer(){
		return tranquilizer;
	}
	
	private  List<Map<String, String>> parseJsonString(String aggregatorJson) {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, String>> aggSpecList = null;
        try {
        	getLogger().debug("Druid Tranquility Service: Aggregator Spec as String: " + aggregatorJson);
            aggSpecList = mapper.readValue(aggregatorJson, List.class);
            getLogger().debug("Druid Tranquility Service: Aggregator Spec as List: " + aggSpecList);
        	return aggSpecList;
        } catch (IOException e) {
            throw new IllegalArgumentException("Exception while parsing the aggregratorJson");
        }
    }
	
	private List<String> getDimensions(String dimensionStringList){
		List<String> dimensionList = new LinkedList(Arrays.asList(dimensionStringList.split(",")));
		return dimensionList;
	}
	
	private List<AggregatorFactory> getAggregatorList(String aggregatorJSON) {
        List<AggregatorFactory> aggregatorList = new LinkedList<>();
        List<Map<String, String>> aggregatorInfo = parseJsonString(aggregatorJSON);
        for (Map<String, String> aggregator : aggregatorInfo) {

            if (aggregator.get("type").equalsIgnoreCase("count")) {
                //Map<String, String> map = aggregator.get("count");
                aggregatorList.add(getCountAggregator(aggregator));
            }
            else if (aggregator.get("type").equalsIgnoreCase("doublesum")) {
                //Map<String, String> map = aggregator.get("doublesum");
                aggregatorList.add(getDoubleSumAggregator(aggregator));
            }
            else if (aggregator.get("type").equalsIgnoreCase("doublemax")) {
                //Map<String, String> map = aggregator.get("doublemax");
                aggregatorList.add(getDoubleMaxAggregator(aggregator));
            }
            else if (aggregator.get("type").equalsIgnoreCase("doublemin")) {
                //Map<String, String> map = aggregator.get("doublemin");
                aggregatorList.add(getDoubleMinAggregator(aggregator));
            }
            else if (aggregator.get("type").equalsIgnoreCase("longsum")) {
                //Map<String, String> map = aggregator.get("longsum");
                aggregatorList.add(getLongSumAggregator(aggregator));
            }
            else if (aggregator.get("type").equalsIgnoreCase("longmax")) {
                //Map<String, String> map = aggregator.get("longmax");
                aggregatorList.add(getLongMaxAggregator(aggregator));
            }
            else if (aggregator.get("type").equalsIgnoreCase("longmin")) {
                //Map<String, String> map = aggregator.get("longmin");
                aggregatorList.add(getLongMinAggregator(aggregator));
            }
        }

        return aggregatorList;
    }
	
	private AggregatorFactory getLongMinAggregator(Map<String, String> map) {
        return new LongMinAggregatorFactory(map.get("name"), map.get("fieldName"));
    }

    private AggregatorFactory getLongMaxAggregator(Map<String, String> map) {
        return new LongMaxAggregatorFactory(map.get("name"), map.get("fieldName"));
    }

    private AggregatorFactory getLongSumAggregator(Map<String, String> map) {
        return new LongSumAggregatorFactory(map.get("name"), map.get("fieldName"));
    }

    private AggregatorFactory getDoubleMinAggregator(Map<String, String> map) {
        return new DoubleMinAggregatorFactory(map.get("name"), map.get("fieldName"));
    }

    private AggregatorFactory getDoubleMaxAggregator(Map<String, String> map) {
        return new DoubleMaxAggregatorFactory(map.get("name"), map.get("fieldName"));
    }

    private AggregatorFactory getDoubleSumAggregator(Map<String, String> map) {
        return new DoubleSumAggregatorFactory(map.get("name"), map.get("fieldName"));
    }

    private AggregatorFactory getCountAggregator(Map<String, String> map) {
        return new CountAggregatorFactory(map.get("name"));
    }
    
    private Granularity getSegmentGranularity(String segmentGranularity) {
        Granularity granularity = Granularity.HOUR;

        switch (segmentGranularity) {
            case "SECOND":
                granularity = Granularity.SECOND;
                break;
            case "MINUTE":
                granularity = Granularity.MINUTE;
                break;
            case "FIVE_MINUTE":
                granularity = Granularity.FIVE_MINUTE;
                break;
            case "TEN_MINUTE":
                granularity = Granularity.TEN_MINUTE;
                break;
            case "FIFTEEN_MINUTE":
                granularity = Granularity.FIFTEEN_MINUTE;
                break;
            case "HOUR":
                granularity = Granularity.HOUR;
                break;
            case "SIX_HOUR":
                granularity = Granularity.SIX_HOUR;
                break;
            case "DAY":
                granularity = Granularity.DAY;
                break;
            case "WEEK":
                granularity = Granularity.WEEK;
                break;
            case "MONTH":
                granularity = Granularity.MONTH;
                break;
            case "YEAR":
                granularity = Granularity.YEAR;
                break;
            default:
                break;
        }
        return granularity;
    }
}
