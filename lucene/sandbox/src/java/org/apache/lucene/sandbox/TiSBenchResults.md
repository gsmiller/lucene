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
| Current TiS  | 212.04           | 312.75            | 356.27           | 120.26        |
| DV           | 26.42            | 28.52             | 25.63            | 164.67        |
| IndexOrDV    | 26.68            | 28.77             | 25.82            | 120.29        |
| Proposed TiS | 27.98            | 29.34             | 25.83            | 111.13        |


### Medium Cardinality + High Cost Country Code Filter Terms
* Term-in-set cardinality: 20 terms
* Term-in-set cost: ranges from 105,131 to 2,240,232 across the 20 terms / avg: 402,173 / total: 8,043,479

Again we see a case where DV massively outperforms the current TiS. Again, both IndexOrDV decide to use DV correctly and
the proposed TiS implementation is slightly slower due to the term seeking. This is another case where the proposed
TiS implementation performs best with no lead terms.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 134.72           | 194.37            | 220.98           | 77.29         |
| DV           | 15.00            | 8.29              | 4.26             | 233.56        |
| IndexOrDV    | 15.02            | 8.33              | 4.31             | 77.29         |
| Proposed TiS | 17.01            | 9.39              | 4.61             | 71.37         |


### Medium Cardinality + Low Cost Country Code Filter Terms
* Term-in-set cardinality: 20 terms
* Term-in-set cost: ranges from 1 to 229 across the 20 terms / avg: 98 / total: 1,951

Here we see that our current TiS outperforms DV due to the low cost of the terms. In this case IndexOrDV get it wrong
while the proposed TiS implementation correctly uses DV. IndexOrDV gets it wrong in this case as it relies on
field-level stats, while the proposed TiS query uses term-level stats. The proposed TiS implementation also does
best with no lead terms.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 4.32             | 5.53              | 5.32             | 0.79          |
| DV           | 10.43            | 6.45              | 3.81             | 93.16         |
| IndexOrDV    | 10.31            | 6.47              | 3.85             | 0.79          |
| Proposed TiS | 2.94             | 3.69              | 3.67             | 0.28          |


### Low Cardinality + High Cost Country Code Filter Terms
* Term-in-set cardinality: 10 terms
* Term-in-set cost: ranges from 243,458 to 2,240,232 across the 10 terms / avg: 645,352 / total: 6,453,524

In this case, doc values are universally better. IndexOrDV figures this out but neither TiS implementation do. This
is because they both get rewritten to a standard boolean query since there are fewer than 16 terms, so they resolve
to an identical (and sub-optimal) implementation. We can do better here with the proposed TiS implementation by not
rewriting to a boolean query.

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 248.00           | 133.53            | 42.60            | 69.69         |
| DV           | 12.46            | 6.30              | 3.06             | 202.68        |
| IndexOrDV    | 15.15            | 10.33             | 7.16             | 69.77         |
| Proposed TiS | 247.94           | 133.57            | 42.59            | 69.80         |


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
| Current TiS  | 3.17             | 4.73              | 5.02             | 0.61          |
| DV           | 15.28            | 10.79             | 8.50             | 107.23        |
| IndexOrDV    | 6.14             | 9.26              | 9.82             | 0.60          |
| Proposed TiS | 2.74             | 4.04              | 4.74             | 0.48          |


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

## Benchmark Results with NO Doc Values
To make sure this change won't significantly impact current `TermInSetQuery` users in a negative way--in the case where
there is no parallel doc value field--I also ran the benchmark tool over all the same use-cases but with no doc values
indexed. Results seem fine to me.

### All Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 382.25           | 568.11            | 660.54           | 164.00        |
| Proposed TiS | 380.30           | 296.28            | 218.90           | 112.23        |

### Medium Cardinality + High Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 174.77           | 253.66            | 293.24           | 91.98         |
| Proposed TiS | 335.83           | 188.64            | 69.10            | 71.46         |

### Medium Cardinality + Low Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 2.53             | 2.77              | 2.68             | 0.26          |
| Proposed TiS | 2.41             | 2.59              | 2.49             | 0.22          |

### Low Cardinality + High Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 236.95           | 132.33            | 43.68            | 93.73         |
| Proposed TiS | 237.04           | 132.46            | 43.69            | 93.60         |

### Low Cardinality + Low Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 2.43             | 3.15              | 3.36             | 0.49          |
| Proposed TiS | 2.43             | 3.14              | 3.36             | 0.49          |

### High Cardinality PK Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 27.41            | 40.62             | 46.28            | 6.58          |
| Proposed TiS | 27.31            | 40.45             | 46.11            | 6.54          |

### Medium Cardinality PK Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 2.31             | 3.40              | 3.89             | 0.51          |
| Proposed TiS | 1.83             | 2.67              | 3.06             | 0.32          |

### Low Cardinality PK Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 1.66             | 2.49              | 2.86             | 0.34          |
| Proposed TiS | 1.66             | 2.49              | 2.86             | 0.34          |
