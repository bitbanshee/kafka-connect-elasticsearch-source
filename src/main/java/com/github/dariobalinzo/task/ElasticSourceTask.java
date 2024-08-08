/*
 * Copyright © 2018 Dario Balinzo (dariobalinzo@gmail.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.dariobalinzo.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dariobalinzo.ElasticSourceConnectorConfig;
import com.github.dariobalinzo.Version;
import com.github.dariobalinzo.elastic.CursorField;
import com.github.dariobalinzo.elastic.ElasticConnection;
import com.github.dariobalinzo.elastic.ElasticConnectionBuilder;
import com.github.dariobalinzo.elastic.ElasticRepository;
import com.github.dariobalinzo.elastic.response.Cursor;
import com.github.dariobalinzo.elastic.response.PageResult;
import com.github.dariobalinzo.filter.BlacklistFilter;
import com.github.dariobalinzo.filter.DocumentFilter;
import com.github.dariobalinzo.filter.JsonCastFilter;
import com.github.dariobalinzo.filter.WhitelistFilter;
import com.github.dariobalinzo.schema.*;
import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import static com.github.dariobalinzo.elastic.ElasticJsonNaming.removeKeywordSuffix;

public class ElasticSourceTask extends SourceTask {

    private static final Logger logger = LoggerFactory.getLogger(ElasticSourceTask.class);
    private static final String INDEX = "index";
    static final String POSITION = "position";
    static final String POSITION_SECONDARY = "position_secondary";


    private final FieldPicker fieldPicker = new FieldPicker();
    private SchemaConverter schemaConverter;
    private StructConverter structConverter;

    private ElasticSourceTaskConfig config;
    private ElasticConnection es;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private List<String> indices;
    private String topic;
    private String cursorSearchField;
    private CursorField cursorField;
    private String secondaryCursorSearchField;
    private CursorField secondaryCursorField;
    private List<String> keyFields;
    private String keyFormat;
    private int pollingMs;
    private final Map<String, Cursor> lastCursor = new HashMap<>();
    private final Map<String, Integer> sent = new HashMap<>();
    private ElasticRepository elasticRepository;
    private String rawFieldName;
    private List<String> mandatoryFieldsOnError;

    private final List<DocumentFilter> documentFilters = new ArrayList<>();

    @Override
    public String version() {
        return Version.VERSION;
    }

    @Override
    public void start(Map<String, String> properties) {
        try {
            config = new ElasticSourceTaskConfig(properties);
        } catch (ConfigException e) {
            throw new ConnectException("Couldn't start ElasticSourceTask due to configuration error", e);
        }

        indices = Arrays.asList(config.getString(ElasticSourceTaskConfig.INDICES_CONFIG).split(","));
        if (indices.isEmpty()) {
            throw new ConnectException("Invalid configuration: each ElasticSourceTask must have at "
                    + "least one index assigned to it");
        }

        topic = config.getString(ElasticSourceConnectorConfig.TOPIC_PREFIX_CONFIG);
        cursorSearchField = config.getString(ElasticSourceConnectorConfig.INCREMENTING_FIELD_NAME_CONFIG);
        Objects.requireNonNull(cursorSearchField, ElasticSourceConnectorConfig.INCREMENTING_FIELD_NAME_CONFIG
                                                  + " conf is mandatory");
        cursorField = new CursorField(cursorSearchField);
        secondaryCursorSearchField = config.getString(ElasticSourceConnectorConfig.SECONDARY_INCREMENTING_FIELD_NAME_CONFIG);
        secondaryCursorField = secondaryCursorSearchField == null ? null : new CursorField(secondaryCursorSearchField);
        keyFormat = config.getString(ElasticSourceTaskConfig.KEY_FORMAT_CONFIG);
        keyFields = Optional.ofNullable(config.getList(ElasticSourceTaskConfig.KEY_FIELDS_CONFIG)).orElse(Collections.emptyList());
        pollingMs = Integer.parseInt(config.getString(ElasticSourceConnectorConfig.POLL_INTERVAL_MS_CONFIG));
        rawFieldName = config.getString(ElasticSourceTaskConfig.RAW_DATA_FIELD_NAME_CONFIG);
        mandatoryFieldsOnError = Optional.ofNullable(config.getList(ElasticSourceTaskConfig.MANDATORY_FIELDS_ON_ERROR_CONFIG)).orElse(Collections.emptyList());

        initConnectorFilters();
        initConnectorFieldConverter();
        initEsConnection();
    }

    private void initConnectorFilters() {
        String whiteFilters = config.getString(ElasticSourceConnectorConfig.VALUE_FILTERS_WHITELIST_CONFIG);
        if (whiteFilters != null) {
            String[] whiteFiltersArray = whiteFilters.split(";");
            Set<String> whiteFiltersSet = new HashSet<>(Arrays.asList(whiteFiltersArray));
            documentFilters.add(new WhitelistFilter(whiteFiltersSet));
        }

        String blackFilters = config.getString(ElasticSourceConnectorConfig.VALUE_FILTERS_BLACKLIST_CONFIG);
        if (blackFilters != null) {
            String[] blackFiltersArray = blackFilters.split(";");
            Set<String> blackFiltersSet = new HashSet<>(Arrays.asList(blackFiltersArray));
            documentFilters.add(new BlacklistFilter(blackFiltersSet));
        }

        String jsonCastFilters = config.getString(ElasticSourceConnectorConfig.VALUE_FILTERS_JSON_CAST_CONFIG);
        if (jsonCastFilters != null) {
            String[] jsonCastFiltersArray = jsonCastFilters.split(";");
            Set<String> whiteFiltersSet = new HashSet<>(Arrays.asList(jsonCastFiltersArray));
            documentFilters.add(new JsonCastFilter(whiteFiltersSet));
        }
    }

    private void initConnectorFieldConverter() {
        String nameConverterConfig = config.getString(ElasticSourceConnectorConfig.CONNECTOR_FIELDNAME_CONVERTER_CONFIG);

        FieldNameConverter fieldNameConverter;
        switch (nameConverterConfig) {
            case ElasticSourceConnectorConfig.NOP_FIELDNAME_CONVERTER:
                fieldNameConverter = new NopNameConverter();
                break;
            case ElasticSourceConnectorConfig.AVRO_FIELDNAME_CONVERTER:
            default:
                fieldNameConverter = new AvroName();
                break;
        }
        this.schemaConverter = new SchemaConverter(fieldNameConverter);
        this.structConverter = new StructConverter(fieldNameConverter);
    }

    private void initEsConnection() {
        String esScheme = config.getString(ElasticSourceConnectorConfig.ES_SCHEME_CONF);
        String esHost = config.getString(ElasticSourceConnectorConfig.ES_HOST_CONF);
        int esPort = Integer.parseInt(config.getString(ElasticSourceConnectorConfig.ES_PORT_CONF));
        Header[] defaultHeaders = Optional
                .ofNullable(
                    config.getList(ElasticSourceConnectorConfig.ES_HEADERS_CONF)
                )
                .orElse(Collections.emptyList())
                .stream()
                .map(s -> s.split(":"))
                .filter(pair -> pair.length > 1)
                .map(pair -> new BasicHeader(pair[0].trim(), pair[1].trim()))
                .toArray(Header[]::new);

        String esUser = config.getString(ElasticSourceConnectorConfig.ES_USER_CONF);
        String esPwd = config.getString(ElasticSourceConnectorConfig.ES_PWD_CONF);

        int batchSize = Integer.parseInt(config.getString(ElasticSourceConnectorConfig.BATCH_MAX_ROWS_CONFIG));

        int maxConnectionAttempts = Integer.parseInt(config.getString(
                ElasticSourceConnectorConfig.CONNECTION_ATTEMPTS_CONFIG
        ));
        long connectionRetryBackoff = Long.parseLong(config.getString(
                ElasticSourceConnectorConfig.CONNECTION_BACKOFF_CONFIG
        ));
        ElasticConnectionBuilder connectionBuilder = new ElasticConnectionBuilder(esHost, esPort)
                .withProtocol(esScheme)
                .withMaxAttempts(maxConnectionAttempts)
                .withBackoff(connectionRetryBackoff)
                .withDefaultHeaders(defaultHeaders);

        String truststore = config.getString(ElasticSourceConnectorConfig.ES_TRUSTSTORE_CONF);
        String truststorePass = config.getString(ElasticSourceConnectorConfig.ES_TRUSTSTORE_PWD_CONF);
        String keystore = config.getString(ElasticSourceConnectorConfig.ES_KEYSTORE_CONF);
        String keystorePass = config.getString(ElasticSourceConnectorConfig.ES_KEYSTORE_PWD_CONF);

        if (truststore != null) {
            connectionBuilder.withTrustStore(truststore, truststorePass);
        }

        if (keystore != null) {
            connectionBuilder.withKeyStore(keystore, keystorePass);
        }

        if (esUser == null || esUser.isEmpty()) {
            es = connectionBuilder.build();
        } else {
            es = connectionBuilder.withUser(esUser)
                    .withPassword(esPwd)
                    .build();
        }

        elasticRepository = new ElasticRepository(es, cursorSearchField, secondaryCursorSearchField);
        elasticRepository.setPageSize(batchSize);
    }


    //will be called by connect with a different thread than the stop thread
    @Override
    public List<SourceRecord> poll() {
        List<SourceRecord> results = new ArrayList<>();
        try {
            for (String index : indices) {
                if (!stopping.get()) {
                    logger.info("fetching from {}", index);
                    Cursor lastValue = fetchLastOffset(index);
                    logger.info("found last value {}", lastValue);
                    PageResult pageResult = secondaryCursorSearchField == null ?
                            elasticRepository.searchAfter(index, lastValue) :
                            elasticRepository.searchAfterWithSecondarySort(index, lastValue);
                    parseResult(pageResult, results);
                    logger.info("index {} total messages: {} ", index, sent.get(index));
                }
            }
            if (results.isEmpty()) {
                logger.info("no data found, sleeping for {} ms", pollingMs);
                Thread.sleep(pollingMs);
            }

        } catch (Exception e) {
            logger.error("error", e);
        }
        return results;
    }

    private Cursor fetchLastOffset(String index) {
        //first we check in cache memory the last value
        if (lastCursor.get(index) != null) {
            return lastCursor.get(index);
        }

        //if cache is empty we check the framework
        Map<String, Object> offset = context.offsetStorageReader().offset(Collections.singletonMap(INDEX, index));
        if (offset != null) {
            String primaryCursor = (String) offset.get(POSITION);
            String secondaryCursor = (String) offset.get(POSITION_SECONDARY);
            return new Cursor(primaryCursor, secondaryCursor);
        } else {
            return Cursor.empty();
        }
    }

    private void parseResult(PageResult pageResult, List<SourceRecord> results) {
        String index = pageResult.getIndex();
        for (Map<String, Object> elasticDocument : pageResult.getDocuments()) {
            Map<String, String> sourcePartition = Collections.singletonMap(INDEX, index);
            Map<String, String> sourceOffset = fieldPicker.pick(
                    elasticDocument,
                    Stream
                        .of(cursorField, secondaryCursorField)
                        .filter(Objects::nonNull)
                        .collect(
                            Collectors.toMap(
                                cf -> cf == cursorField ? POSITION : POSITION_SECONDARY,
                                Function.identity()
                            )
                        )
            );
            Map<String, Object> keySkeleton = fieldPicker.pick(
                    elasticDocument,
                    Stream
                        .concat(
                            Stream
                                .of(cursorSearchField, secondaryCursorSearchField)
                                .filter(Objects::nonNull),
                            keyFields.stream()
                        )
                        .filter(((Predicate<String>)String::isEmpty).negate())
                        .collect(Collectors.toList())
                        .toArray(new String[0])
            );
            Schema keySchema;
            Object key;
            if (keyFormat == ElasticSourceTaskConfig.KEY_FORMAT_STRING) {
                keySchema = Schema.STRING_SCHEMA;
                key = keySkeleton
                    .keySet()
                    .stream()
                    .collect(Collectors.joining("_", index + "_", ""));
            } else {
                keySkeleton.put("es_index", index);
                keySchema = schemaConverter.convert(keySkeleton, index + "_key");
                key = structConverter.convert(keySkeleton, keySchema);
            }
            
            lastCursor.put(index, pageResult.getLastCursor());
            sent.merge(index, 1, Integer::sum);

            documentFilters.forEach(jsonFilter -> jsonFilter.filter(elasticDocument));

            Schema schema;
            Struct struct;
            try {
                schema = schemaConverter.convert(elasticDocument, index);
                struct = structConverter.convert(elasticDocument, schema);
            } catch(Exception e) {
                logger.error("Could not serialize document in Kafka Connect format. " + 
                    "Raw value of document will be placed into field {} as string.", rawFieldName, e);
                Map<String, Object> skeleton = fieldPicker.pick(
                    elasticDocument,
                    mandatoryFieldsOnError
                        .stream()
                        .filter(((Predicate<String>)String::isEmpty).negate())
                        .collect(Collectors.toList())
                        .toArray(new String[0])
                );
                try {
                    skeleton.put(rawFieldName, objectMapper.writeValueAsString(elasticDocument));
                }
                // this should not happen
                catch (JsonProcessingException jsonError) {
                    logger.error("Could not serialize document as JSON. " +
                        "Raw value of document will be placed into field {} as Java map.", rawFieldName, jsonError);
                    skeleton.put(rawFieldName, elasticDocument.toString());
                }
                schema = schemaConverter.convert(skeleton, index);
                struct = structConverter.convert(skeleton, schema);
            }

            SourceRecord sourceRecord = new SourceRecord(
                    sourcePartition,
                    sourceOffset,
                    topic + index,
                    //KEY
                    keySchema,
                    key,
                    //VALUE
                    schema,
                    struct);
            results.add(sourceRecord);
        }
    }

    //will be called by connect with a different thread than poll thread
    public void stop() {
        stopping.set(true);
        if (es != null) {
            es.closeQuietly();
        }
    }
}
