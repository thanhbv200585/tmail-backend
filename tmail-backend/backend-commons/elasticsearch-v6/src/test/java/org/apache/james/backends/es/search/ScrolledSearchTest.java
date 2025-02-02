/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.backends.es.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.FIVE_SECONDS;

import java.io.IOException;

import org.apache.james.backends.es.DockerElasticSearchExtension;
import org.apache.james.backends.es.ElasticSearchConfiguration;
import org.apache.james.backends.es.IndexCreationFactory;
import org.apache.james.backends.es.IndexName;
import org.apache.james.backends.es.NodeMappingFactory;
import org.apache.james.backends.es.ReactorElasticSearchClient;
import org.apache.james.backends.es.ReadAliasName;
import org.awaitility.core.ConditionFactory;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ScrolledSearchTest {
    private static final TimeValue TIMEOUT = TimeValue.timeValueMinutes(1);
    private static final int SIZE = 2;
    private static final String MESSAGE = "message";
    private static final IndexName INDEX_NAME = new IndexName("index");
    private static final ReadAliasName ALIAS_NAME = new ReadAliasName("alias");

    private static final ConditionFactory WAIT_CONDITION = await().timeout(FIVE_SECONDS);

    @RegisterExtension
    public DockerElasticSearchExtension elasticSearch = new DockerElasticSearchExtension();
    private ReactorElasticSearchClient client;

    @BeforeEach
    void setUp() {
        client = elasticSearch.getDockerElasticSearch().clientProvider().get();
        new IndexCreationFactory(ElasticSearchConfiguration.DEFAULT_CONFIGURATION)
            .useIndex(INDEX_NAME)
            .addAlias(ALIAS_NAME)
            .createIndexAndAliases(client);
        elasticSearch.awaitForElasticSearch();
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
    }

    @Test
    void scrollIterableShouldWorkWhenEmpty() {
        SearchRequest searchRequest = new SearchRequest(INDEX_NAME.value())
            .scroll(TIMEOUT)
            .source(new SearchSourceBuilder()
                .query(QueryBuilders.matchAllQuery())
                .size(SIZE));

        assertThat(new ScrolledSearch(client, searchRequest).searchHits().collectList().block())
            .isEmpty();
    }

    @Test
    void scrollIterableShouldWorkWhenOneElement() {
        String id = "1";
        client.index(new IndexRequest(INDEX_NAME.value())
                .type(NodeMappingFactory.DEFAULT_MAPPING_NAME)
                .id(id)
                .source(MESSAGE, "Sample message"),
            RequestOptions.DEFAULT)
        .block();

        elasticSearch.awaitForElasticSearch();
        WAIT_CONDITION.untilAsserted(() -> hasIdsInIndex(client, id));

        SearchRequest searchRequest = new SearchRequest(INDEX_NAME.value())
            .scroll(TIMEOUT)
            .source(new SearchSourceBuilder()
                .query(QueryBuilders.matchAllQuery())
                .size(SIZE));

        assertThat(new ScrolledSearch(client, searchRequest).searchHits().collectList().block())
            .extracting(SearchHit::getId)
            .containsOnly(id);
    }

    @Test
    void scrollIterableShouldWorkWhenSizeElement() {
        String id1 = "1";
        client.index(new IndexRequest(INDEX_NAME.value())
                .type(NodeMappingFactory.DEFAULT_MAPPING_NAME)
                .id(id1)
                .source(MESSAGE, "Sample message"),
            RequestOptions.DEFAULT)
            .block();

        String id2 = "2";
        client.index(new IndexRequest(INDEX_NAME.value())
                .type(NodeMappingFactory.DEFAULT_MAPPING_NAME)
                .id(id2)
                .source(MESSAGE, "Sample message"),
            RequestOptions.DEFAULT)
            .block();

        elasticSearch.awaitForElasticSearch();
        WAIT_CONDITION.untilAsserted(() -> hasIdsInIndex(client, id1, id2));

        SearchRequest searchRequest = new SearchRequest(INDEX_NAME.value())
            .scroll(TIMEOUT)
            .source(new SearchSourceBuilder()
                .query(QueryBuilders.matchAllQuery())
                .size(SIZE));

        assertThat(new ScrolledSearch(client, searchRequest).searchHits().collectList().block())
            .extracting(SearchHit::getId)
            .containsOnly(id1, id2);
    }

    @Test
    void scrollIterableShouldWorkWhenMoreThanSizeElement() {
        String id1 = "1";
        client.index(new IndexRequest(INDEX_NAME.value())
                .type(NodeMappingFactory.DEFAULT_MAPPING_NAME)
                .id(id1)
                .source(MESSAGE, "Sample message"),
            RequestOptions.DEFAULT)
            .block();

        String id2 = "2";
        client.index(new IndexRequest(INDEX_NAME.value())
                .type(NodeMappingFactory.DEFAULT_MAPPING_NAME)
                .id(id2)
                .source(MESSAGE, "Sample message"),
            RequestOptions.DEFAULT)
            .block();

        String id3 = "3";
        client.index(new IndexRequest(INDEX_NAME.value())
                .type(NodeMappingFactory.DEFAULT_MAPPING_NAME)
                .id(id3)
                .source(MESSAGE, "Sample message"),
            RequestOptions.DEFAULT)
            .block();

        elasticSearch.awaitForElasticSearch();
        WAIT_CONDITION.untilAsserted(() -> hasIdsInIndex(client, id1, id2, id3));

        SearchRequest searchRequest = new SearchRequest(INDEX_NAME.value())
            .scroll(TIMEOUT)
            .source(new SearchSourceBuilder()
                .query(QueryBuilders.matchAllQuery())
                .size(SIZE));

        assertThat(new ScrolledSearch(client, searchRequest).searchHits().collectList().block())
            .extracting(SearchHit::getId)
            .containsOnly(id1, id2, id3);
    }

    private void hasIdsInIndex(ReactorElasticSearchClient client, String... ids) {
        SearchRequest searchRequest = new SearchRequest(INDEX_NAME.value())
            .scroll(TIMEOUT)
            .source(new SearchSourceBuilder()
                .query(QueryBuilders.matchAllQuery()));

        SearchHit[] hits = client.search(searchRequest, RequestOptions.DEFAULT)
            .block()
            .getHits()
            .getHits();

        assertThat(hits)
            .extracting(SearchHit::getId)
            .contains(ids);
    }
}
