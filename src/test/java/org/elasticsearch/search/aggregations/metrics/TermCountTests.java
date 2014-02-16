/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.metrics;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.metrics.termcount.HyperLogLogPlusPlus;
import org.elasticsearch.search.aggregations.metrics.termcount.TermCount;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.junit.Before;
import org.junit.Test;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.search.aggregations.AggregationBuilders.termCount;
import static org.elasticsearch.search.aggregations.AggregationBuilders.terms;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.IsNull.notNullValue;

public class TermCountTests extends ElasticsearchIntegrationTest {

    @Override
    public Settings indexSettings() {
        return ImmutableSettings.builder()
                .put("index.number_of_shards", between(1, 5))
                .put("index.number_of_replicas", between(0, 1))
                .build();
    }

    long numDocs;
    int precision;

    @Before
    public void init() throws Exception {
        prepareCreate("idx").addMapping("type",
                jsonBuilder().startObject().startObject("type").startObject("properties")
                    .startObject("str_value")
                        .field("type", "multi_field")
                        .startObject("fields")
                            .startObject("str_value")
                                .field("type", "string")
                            .endObject()
                            .startObject("hash")
                                .field("type", "murmur3")
                            .endObject()
                        .endObject()
                    .endObject()
                    .startObject("str_values")
                        .field("type", "multi_field")
                        .startObject("fields")
                            .startObject("str_values")
                                .field("type", "string")
                            .endObject()
                            .startObject("hash")
                                .field("type", "murmur3")
                            .endObject()
                        .endObject()
                    .endObject()
                    .startObject("num_value")
                        .field("type", "multi_field")
                        .startObject("fields")
                            .startObject("num_value")
                                .field("type", "long")
                            .endObject()
                            .startObject("hash")
                                .field("type", "murmur3")
                            .endObject()
                        .endObject()
                    .endObject()
                    .startObject("num_values")
                        .field("type", "multi_field")
                        .startObject("fields")
                            .startObject("num_values")
                                .field("type", "long")
                            .endObject()
                            .startObject("hash")
                                .field("type", "murmur3")
                            .endObject()
                        .endObject()
                    .endObject()
                .endObject().endObject().endObject()).execute().actionGet();

        numDocs = randomIntBetween(2, 100);
        precision = randomIntBetween(HyperLogLogPlusPlus.MIN_PRECISION, HyperLogLogPlusPlus.MAX_PRECISION);
        IndexRequestBuilder[] builders = new IndexRequestBuilder[(int) numDocs];
        for (int i = 0; i < numDocs; ++i) {
            builders[i] = client().prepareIndex("idx", "type").setSource(jsonBuilder()
                    .startObject()
                        .field("str_value", "s" + i)
                        .field("str_values", new String[] {"s" + (i * 2), "s" + (i * 2 + 1)})
                        .field("num_value", i)
                        .field("num_values", new int[] {i * 2, i * 2 + 1})
                    .endObject());
        }
        indexRandom(true, builders);
        createIndex("idx_unmapped");
        ensureSearchable();
    }

    private void assertCount(TermCount count, long value) {
        if (value <= (1 << precision) * 3 / 16) {
            // linear counting should be picked, and should be accurate
            assertEquals(value, count.getValue());
        } else {
            // error is not bound, so let's just make sure it is > 0
            assertThat(count.getValue(), greaterThan(0L));
        }
    }

    @Test
    public void unmapped() throws Exception {
        SearchResponse response = client().prepareSearch("idx_unmapped").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("str_value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, 0);
    }

    @Test
    public void partiallyUnmapped() throws Exception {
        SearchResponse response = client().prepareSearch("idx", "idx_unmapped").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("str_value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs);
    }

    @Test
    public void singleValuedString() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("str_value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs);
    }

    @Test
    public void singleValuedStringHashed() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("str_value.hash"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs);
    }

    @Test
    public void singleValuedNumeric() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("num_value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs);
    }

    @Test
    public void singleValuedNumericHashed() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("num_value.hash"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs);
    }

    @Test
    public void multiValuedString() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("str_values"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs * 2);
    }

    @Test
    public void multiValuedStringHashed() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("str_values.hash"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs * 2);
    }

    @Test
    public void multiValuedNumeric() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("num_values"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs * 2);
    }

    @Test
    public void multiValuedNumericHashed() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("num_values.hash"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs * 2);
    }

    @Test
    public void singleValuedStringScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).script("doc['str_value'].value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs);
    }

    @Test
    public void multiValuedStringScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).script("doc['str_values'].values"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs * 2);
    }

    @Test
    public void singleValuedNumericScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).script("doc['num_value'].value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs);
    }

    @Test
    public void multiValuedNumericScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).script("doc['num_values'].values"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs * 2);
    }

    @Test
    public void singleValuedStringValueScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("str_value").script("_value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs);
    }

    @Test
    public void multiValuedStringValueScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("str_values").script("_value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs * 2);
    }

    @Test
    public void singleValuedNumericValueScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("num_value").script("_value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs);
    }

    @Test
    public void multiValuedNumericValueScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(termCount("count").precision(precision).field("num_values").script("_value"))
                .execute().actionGet();

        assertSearchResponse(response);

        TermCount count = response.getAggregations().get("count");
        assertThat(count, notNullValue());
        assertThat(count.getName(), equalTo("count"));
        assertCount(count, numDocs * 2);
    }

    @Test
    public void asSubAgg() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms").field("str_value").subAggregation(termCount("count").precision(precision).field("str_values")))
                .execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        for (Terms.Bucket bucket : terms.getBuckets()) {
            TermCount count = bucket.getAggregations().get("count");
            assertThat(count, notNullValue());
            assertThat(count.getName(), equalTo("count"));
            assertCount(count, 2);
        }
    }

    @Test
    public void asSubAggHashed() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms").field("str_value").subAggregation(termCount("count").precision(precision).field("str_values.hash")))
                .execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        for (Terms.Bucket bucket : terms.getBuckets()) {
            TermCount count = bucket.getAggregations().get("count");
            assertThat(count, notNullValue());
            assertThat(count.getName(), equalTo("count"));
            assertCount(count, 2);
        }
    }

}
