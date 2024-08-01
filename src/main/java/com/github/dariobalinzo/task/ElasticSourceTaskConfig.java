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


import com.github.dariobalinzo.ElasticSourceConnectorConfig;
import org.apache.kafka.common.config.ConfigDef;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Configuration options for a single ElasticSourceTask. These are processed after all
 * Connector-level configs have been parsed.
 */
public class ElasticSourceTaskConfig extends ElasticSourceConnectorConfig {

    private static final String TASK_GROUP = "Task";

    public static final String INDICES_CONFIG = "es.indices";
    
    public static final String KEY_FIELDS_CONFIG = "key.fields";
    public static final ConfigDef.Type KEY_FIELDS_TYPE = ConfigDef.Type.LIST;
    private static final List<String> KEY_FIELDS_DEFAULT = null;
    private static final String KEY_FIELDS_DOC = "List of fields to be included in the key structure. " + 
        "Default fields are the index name, the ones provided via '" + INCREMENTING_FIELD_NAME_CONFIG + "' " +
        "and '" + SECONDARY_INCREMENTING_FIELD_NAME_CONFIG + "'. " +
        "Default fields are always included. Example: 'order.qty,order.price,user.name'";
    private static final String KEY_FIELDS_DISPLAY = "List of fields whose values will be used to build keys";
    private static final ConfigDef.Importance KEY_FIELDS_IMPORTANCE = ConfigDef.Importance.LOW;

    public static final String KEY_FORMAT_CONFIG = "key.format";
    public static final ConfigDef.Type KEY_FORMAT_TYPE =ConfigDef.Type.STRING;
    public static final String KEY_FORMAT_STRING = "string";
    public static final String KEY_FORMAT_STRUCT = "struct";
    public static final ConfigDef.Validator KEY_FORMAT_VALIDATOR = ConfigDef.ValidString.in(KEY_FORMAT_STRING, KEY_FORMAT_STRUCT);
    private static final String KEY_FORMAT_DOC = "Record's key format. Keys are built using the fields " + 
        "defined by '" + KEY_FIELDS_CONFIG + "' and serialized using the provided format. Supported formats " +
        "are: '" + KEY_FORMAT_STRING + "' and '" + KEY_FORMAT_STRUCT + "'";
    private static final String KEY_FORMAT_DEFAULT = KEY_FORMAT_STRING;
    private static final String KEY_FORMAT_DISPLAY = "Record's key format";
    private static final ConfigDef.Importance KEY_FORMAT_IMPORTANCE = ConfigDef.Importance.LOW;

    static ConfigDef config = baseConfigDef()
            .define(
                INDICES_CONFIG,
                ConfigDef.Type.STRING,
                ConfigDef.Importance.HIGH,
                INDICES_CONFIG
            ).define(
                KEY_FORMAT_CONFIG,
                KEY_FORMAT_TYPE,
                KEY_FORMAT_DEFAULT,
                KEY_FORMAT_VALIDATOR,
                KEY_FORMAT_IMPORTANCE,
                KEY_FORMAT_DOC,
                TASK_GROUP,
                1,
                ConfigDef.Width.MEDIUM,
                KEY_FORMAT_DISPLAY
            ).define(
                KEY_FIELDS_CONFIG,
                KEY_FIELDS_TYPE,
                KEY_FIELDS_DEFAULT,
                KEY_FIELDS_IMPORTANCE,
                KEY_FIELDS_DOC,
                TASK_GROUP,
                2,
                ConfigDef.Width.MEDIUM,
                KEY_FIELDS_DISPLAY,
                Stream.of(KEY_FORMAT_CONFIG).collect(Collectors.toList())
            );

    public ElasticSourceTaskConfig(Map<String, String> props) {
        super(config, props);
    }
}
