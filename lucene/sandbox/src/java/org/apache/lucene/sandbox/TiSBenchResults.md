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

## Goals
1. Gather some data on when postings vs. doc-values are efficient in evaluating a term-in-set clause.
2. Determine if a custom, self-optimizing, term-in-set query provides any benefits over the existing
   `IndexOrDocValuesQuery` on top of the existing `TermInSetQuery` + `DocValuesTermsQuery`.

## Setup
Benchmarks run against an index using `allCountries.txt` geonames data. Index created with:
* "name" field that indexed the parsed name of each record
* "cc" field that indexed the country code ID for each record (single valued)
* "id" field that indexed the unique ID for each record (single valued)

Each benchmark query "leads" with a single term matching against the "name" field and included a required conjunction
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
the proposed `TermInSetQuery`, as the existing `IndexOrDocValuesQuery` query sometimes incorrectly decides to use a
doc-values approach when postings would be better (they almost always are better for primary keys). This is because
the `#cost()` estimation of `TermInSetQuery`, while specialized for primary-key cases, sort of assumes that all terms
will occur in the segment, which is probably quite wrong for primary key cases.

### All Country Code Filter Terms
* Term-in-set cardinality: 254 terms
* Term-in-set cost: Entire index / all documents

Here we see a DV approach massively outperforms the current TiS approach. IndexOrDV figures this out, as does the
proposed TiS implementation. IndexOrDV is slightly better here, probably due to not doing any term seeking before
deciding to use DV. With no lead terms, the proposed TiS implementation performs best, presumably due to the
term-at-a-time bulk scoring approach.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 376.53           | 559.65            | 640.83           | 159.97        |
| DV           | 28.54            | 31.90             | 29.05            | 161.41        |
| IndexOrDV    | 28.80            | 32.13             | 29.25            | 160.01        |
| Proposed TiS | 30.04            | 32.61             | 29.22            | 111.79        |


### Medium Cardinality + High Cost Country Code Filter Terms
* Term-in-set cardinality: 20 terms
* Term-in-set cost: ranges from 105,131 to 2,240,232 across the 20 terms / avg: 402,173 / total: 8,043,479

Again we see a case where DV massively outperforms the current TiS. Again, both IndexOrDV decide to use DV correctly and
the proposed TiS implementation is slightly slower due to the term seeking. This is another case where the proposed
TiS implementation performs best with no lead terms.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 172.72           | 251.50            | 286.89           | 85.86         |
| DV           | 14.84            | 8.31              | 4.47             | 228.90        |
| IndexOrDV    | 14.87            | 8.35              | 4.51             | 85.87         |
| Proposed TiS | 17.13            | 9.63              | 4.92             | 71.71         |


### Medium Cardinality + Low Cost Country Code Filter Terms
* Term-in-set cardinality: 20 terms
* Term-in-set cost: ranges from 1 to 229 across the 20 terms / avg: 98 / total: 1,951

Here we see that our current TiS outperforms DV due to the low cost of the terms. In this case IndexOrDV get it wrong
while the proposed TiS implementation correctly uses DV. IndexOrDV gets it wrong in this case as it relies on
field-level stats, while the proposed TiS query uses term-level stats. The proposed TiS implementation also does
best with no lead terms.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 4.30             | 5.52              | 5.29             | 0.79          |
| DV           | 9.86             | 6.49              | 4.06             | 89.82         |
| IndexOrDV    | 10.27            | 6.65              | 4.13             | 0.79          |
| Proposed TiS | 3.04             | 3.87              | 3.91             | 0.28          |


### Low Cardinality + High Cost Country Code Filter Terms
* Term-in-set cardinality: 10 terms
* Term-in-set cost: ranges from 243,458 to 2,240,232 across the 10 terms / avg: 645,352 / total: 6,453,524

In this case, doc values are universally better. IndexOrDV figures this out but neither TiS implementation do. This
is because they both get rewritten to a standard boolean query since there are fewer than 16 terms, so they resolve
to an identical (and sub-optimal) implementation. We can do better here with the proposed TiS implementation by not
rewriting to a boolean query.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 246.48           | 133.08            | 42.72            | 92.96         |
| DV           | 12.41            | 6.39              | 3.21             | 203.99        |
| IndexOrDV    | 15.09            | 10.52             | 7.42             | 92.94         |
| Proposed TiS | 246.42           | 132.96            | 42.76            | 92.95         |


### Low Cardinality + Low Cost Country Code Filter Terms
* Term-in-set cardinality: 10 terms
* Term-in-set cost: ranges from 1 to 97 across the 10 terms / avg: 49 / total: 489

A postings approach is generally better here due to the lost cost of the filter terms. The current TiS implementation
and the proposed one both get this right somewhat "accidentally" because they both rewrite into a standard boolean
query due to there being fewer than 16 terms. IndexOrDV also figures this out.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 3.02             | 4.18              | 4.40             | 0.65          |
| DV           | 9.58             | 5.58              | 3.04             | 96.95         |
| IndexOrDV    | 4.06             | 5.72              | 5.81             | 0.65          |
| Proposed TiS | 3.02             | 4.25              | 4.30             | 0.65          |


### High Cardinality PK Filter Terms
* Term-in-set cardinality: 500 terms
* Term-in-set cost: exactly 1 for each term / total cost of 500 across all terms

In this situation, a postings-approach is generally better than a DV approach. Even though there are 500 individual
filter terms, because the field is a PK, the number of terms in each segment is much lower. The cost estimation
in our current TermInSetQuery has special-case logic for primary-key fields, but it overestimates the cost due to
this issue, meaning IndexOrDV is incorrectly choosing to use DV. Our proposed TiS approach does a better job, but
is still using DVs in some cases due to the heuristics not being perfect (confirmed through profiling). We can probably
tune to be better here.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 38.48            | 57.32             | 59.25            | 9.35          |
| DV           | 101.64           | 134.21            | 134.31           | 160.87        |
| IndexOrDV    | 122.97           | 171.60            | 149.22           | 9.70          |
| Proposed TiS | 38.92            | 78.89             | 116.52           | 9.15          |


### Medium Cardinality PK Filter Terms
* Term-in-set cardinality: 20 terms
* Term-in-set cost: exactly 1 for each term / total cost of 20 across all terms

This is a similar story to above, but our proposed TiSQuery gets it right more often, producing better results.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 3.27             | 4.89              | 5.19             | 0.62          |
| DV           | 14.81            | 10.82             | 8.59             | 107.99        |
| IndexOrDV    | 6.19             | 9.52              | 10.18            | 0.62          |
| Proposed TiS | 2.91             | 4.33              | 5.01             | 0.51          |


### Low Cardinality PK Filter Terms
* Term-in-set cardinality: 10 terms
* Term-in-set cost: exactly 1 for each term / total cost of 10 across all terms

In this case, because there are fewer than 16 terms, the current TiS and proposed TiS implementations are identical
(both rewriting to boolean queries).

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 2.51             | 3.91              | 4.08             | 0.52          |
| DV           | 12.22            | 7.82              | 5.73             | 99.97         |
| IndexOrDV    | 3.61             | 5.41              | 5.93             | 0.53          |
| Proposed TiS | 2.52             | 3.83              | 4.11             | 0.53          |