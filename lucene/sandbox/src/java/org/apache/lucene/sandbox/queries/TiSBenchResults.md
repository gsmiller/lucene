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
## Setup
Benchmarks run against an index using `allCountries.txt` geonames data. Index created with:
* "name" field that indexed the parsed name of each record
* "cc" field that indexed the country code ID for each record (single valued)
* "id" field that indexed the unique ID for each record (single valued)

Each benchmark query "lead" with a single term matching against the "name" field and included a required conjunction
over a disjunction of country codes (e.g., a "term-in-set" query that required one of the specified country codes
for each hit to be considered a match). Details on the distributions of these terms for each task are below, followed by
the results.

The benchmark results compare:
1. Our current `TermInSetQuery` implementation (always use postings for the country code clause)
2. Our current `DocValuesTermsQuery` implementation (always use doc values for the country code clause)
3. Our current `IndexOrDocValuesQuery` wrapping #1 and #2
4. A proposed new sandbox `TermInSetQuery` that acts a bit like `IndexOrDocValuesQuery` but goes beyond just using
field-level stats when deciding which approach to take, starts to peek at individual term stats.

### Benchmark Term Distributions
#### "Large" lead term counts:
* "la" -> 181,425
* "de" -> 171,095
* "saint" -> 62,831
* "canyon" -> 27,503

#### "Medium" lead term counts:
* "hotel" -> 64,349
* "del" -> 37,469
* "les" -> 13,111
* "plaza" -> 10,605
* "parc" -> 2,287
* "by" -> 6,794

#### "Small" lead term counts:
* "channel" -> 4,186
* "centre" -> 4,615
* "st" -> 6,616
* "imperial" -> 663
* "silent" -> 99
* "sant" -> 863
* "andorra" -> 49

#### Country Code filter term counts:
#### High cost terms
NOTE: First 10 used for "low cardinality" tasks, all 20 used for "high cardinality"

* 2240232 US
* 874153 CN
* 649062 IN
* 607434 NO
* 455292 MX
* 447465 ID
* 366929 RU
* 314332 CA
* 255167 TH
* 243458 IR
* 225948 PK
* 214006 AU
* 199201 DE
* 169307 FR
* 153182 MA
* 140885 NP
* 132473 KR
* 129968 BR
* 119854 IT
* 105131 PE

#### Low cost terms
NOTE: First 10 used for "low cardinality" tasks, all 20 used for "high cardinality"

*  1 YU
*  2 AN
*  2 CS
*  41 PN
*  47 BV
*  60 CC
*  71 SM
*  81 NF
*  87 CX
*  97 HM
*  99 MC
*  109 NR
*  115 VA
*  118 TK
*  119 IO
*  132 BL
*  168 IM
*  174 MO
*  199 SX
*  229 MF

## Benchmark Results
In general, the proposed `TermInSetQuery` tends to perform at least as well as `IndexOrDocValuesQuery` and better in
some specific cases. These cases are where `IndexOrDocValuesQuery` incorrectly assumes a doc values approach will be
better after looking at field-level stats, and the proposed `TermInSetQuery` makes a more informed decision to use
doc values after loading some term-level stats. Primary-key cases also seem to perform significantly better with
the proposed `TermInSetQuery`.

### All Country Code Filter Terms
* Term-in-set cardinality: 254 terms
* Term-in-set cost: Entire index / all documents

Here we see a DV approach massively outperforms the current TiS approach. IndexOrDV figures this out, as does the
proposed TiS implementation. IndexOrDV is slightly better here, probably due to not doing any term seeking before
deciding to use DV.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 115.65           | 172.26            | 193.97           |
| DV           | 4.88             | 6.06              | 5.71             |
| IndexOrDV    | 4.96             | 6.19              | 5.88             |
| Proposed TiS | 5.64             | 6.73              | 5.89             |


### Medium Cardinality + High Cost Country Code Filter Terms
* Term-in-set cardinality: 20 terms
* Term-in-set cost: ranges from 105,131 to 2,240,232 across the 20 terms / avg: 402,173 / total: 8,043,479

Again we see a case where DV massively outperforms the current TiS. Again, both IndexOrDV decide to use DV correctly and
the proposed TiS implementation is slightly slower due to the term seeking.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 62.32            | 92.33             | 107.02           |
| DV           | 3.29             | 2.42              | 1.90             |
| IndexOrDV    | 3.31             | 2.45              | 1.94             |
| Proposed TiS | 3.94             | 3.09              | 2.47             |


### Medium Cardinality + Low Cost Country Code Filter Terms
* Term-in-set cardinality: 20 terms
* Term-in-set cost: ranges from 1 to 229 across the 20 terms / avg: 98 / total: 1,951

Here we see that our current TiS outperforms DV due to the low cost of the terms. In this case IndexOrDV get it wrong
while the proposed TiS implementation correctly uses DV. IndexOrDV gets it wrong in this case as it relies on
field-level stats, while the proposed TiS query uses term-level stats.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 1.01             | 1.45              | 1.47             |
| DV           | 14.73            | 5.48              | 1.65             |
| IndexOrDV    | 14.75            | 5.52              | 1.69             |
| Proposed TiS | 0.99             | 1.39              | 1.42             |


### Low Cardinality + High Cost Country Code Filter Terms
* Term-in-set cardinality: 10 terms
* Term-in-set cost: ranges from 243,458 to 2,240,232 across the 10 terms / avg: 645,352 / total: 6,453,524

Here we see that our current TiS is better for both "large" and "medium" lead terms, but DV is best for a "small" lead.
Because the number of filter terms is low (< 16 specifically), our current TiSQuery gets rewritten to a boolean query.
Based on its cost estimation, which looks at the actual term-stats, IndexOrDV chooses to always use DV, which is
less-than ideal, particularly for the "large" lead case (but the correct choice for the "small" lead case). Our
proposed TiSQuery makes the same "mistakes" as our current IndexOrDV query as it's also looking at term-level stats,
and decides DV should be better in all cases. I think this is just a situation where it's "unexpected" based on index
stats that a postings-approach would be better given the high cost across the terms, but the arrangement of the docs
in the postings relative to the lead term just happens to work out in a bit of a surprising way.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 2.88             | 3.43              | 3.01             |
| DV           | 5.63             | 3.93              | 1.86             |
| IndexOrDV    | 6.27             | 4.91              | 2.86             |
| Proposed TiS | 6.14             | 4.49              | 2.33             |


### Low Cardinality + Low Cost Country Code Filter Terms
* Term-in-set cardinality: 10 terms
* Term-in-set cost: ranges from 1 to 97 across the 10 terms / avg: 49 / total: 489

Here we see that our current TiS is better than DV. This is expected as the filter terms all have such low costs, so
a postings-approach is cheaper than a DV approach (although the two almost converge when the lead term gets "small").
This is an interesting case for IndexOrDV. While IndexOrDV generally uses field-level statistics and would be expected
to get it "wrong" in this case (and use DV due to the over-estimate on cost at the field-level), the "index" query
(TiSQuery) get rewritten to a standard BooleanQuery as there are fewer than 16 terms, and provides a more accurate
cost by actually seeking the 16 terms and looking at term-level statistics. So IndexOrDV correctly chooses to use
postings, but our proposed TiS implementation also correctly chooses to use postings and is a bit more efficient, likely
explained by the choice to pre-populate a bitset from all terms up front due to their small size vs. maintaining a
heap and doing doc-at-a-time scoring (as with the standard BooleanQuery that the current TiSQuery rewrites to).

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 0.80             | 1.20              | 1.28             |
| DV           | 14.23            | 5.15              | 1.44             |
| IndexOrDV    | 1.06             | 1.55              | 1.66             |
| Proposed TiS | 0.69             | 0.96              | 1.05             |


### High Cardinality PK Filter Terms
* Term-in-set cardinality: 500 terms
* Term-in-set cost: exactly 1 for each term / total cost of 500 across all terms

In this situation, a postings-approach is generally better than a DV approach. Even though there are 500 individual
filter terms, because the field is a PK, the overall cost is still relatively low (500 total), so it's generally
better to use postings over DV, but in some cases--where the lead term has a very low cost--DV might out-perform.
Both IndexOrDV and the proposed TiSQuery make different decisions between postings and DV depending on the cost of
the specific lead term, and generally do the right thing. I suspect the reason IndexOrDV performs worse than
the proposed TiSQuery has something to do with the 8x cost differential it uses to more strongly prefer postings,
which may not really be the right thing to do all the time. The proposed TiSQuery is more aggressive about switching
to DV when the lead cost is lower than the number of terms (500), while IndexOrDV will only do so when the lead cost
is 8x cheaper than 500.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 17.54            | 25.71             | 24.98            |
| DV           | 63.72            | 72.83             | 67.27            |
| IndexOrDV    | 60.53            | 82.03             | 76.00            |
| Proposed TiS | 35.26            | 56.62             | 62.80            |


### Medium Cardinality PK Filter Terms
* Term-in-set cardinality: 20 terms
* Term-in-set cost: exactly 1 for each term / total cost of 20 across all terms

This is a similar story to above, but postings outperforms DV in general even more due to the lower cardinality of
the filter terms. Because of the 8x multiplier in IndexOrDV, it _never_ chooses a DV approach, while the proposed TiS
query does occasionally, which probably accounts for the performance benefit.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 1.74             | 2.57              | 2.59             |
| DV           | 19.23            | 10.02             | 6.10             |
| IndexOrDV    | 4.39             | 6.50              | 6.63             |
| Proposed TiS | 1.79             | 3.32              | 3.49             |


### Low Cardinality PK Filter Terms
* Term-in-set cardinality: 10 terms
* Term-in-set cost: exactly 1 for each term / total cost of 10 across all terms

This is yet again the same story as above. IndexOrDV always uses postings, while the proposed TiSQuery does sometimes
use DV, which is better in some cases, so it outperforms.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 1.39             | 2.01              | 2.02             |
| DV           | 16.36            | 8.17              | 4.42             |
| IndexOrDV    | 3.55             | 5.20              | 5.29             |
| Proposed TiS | 1.11             | 2.09              | 2.11             |