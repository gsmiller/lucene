## Background
Some search use-cases require evaluating relatively large "term-in-set" clauses, testing whether-or-not a given doc
matches at least one term in a provided set of terms (within a specific field). These clauses may be used at allow-
or deny-lists, but do not contribute to the score of a doc. Here are two real-world, motivating scenarios:

First, imagine searching over a catalog of products, where products have been assigned a
[UNSPSC](https://en.wikipedia.org/wiki/UNSPSC) categorization identifier. At query-time, we may need to restrict search 
results based on these codes, either filtering out or only including products found within certain category codes. The
list of codes we're interested in including/excluding can be modeled as a term disjunction contained within a `FILTER`
or `MUST_NOT` boolean clause. In this case, the relationship between a UNSPSC identifier and documents is one-to-many
within our index.

Next, imagine building a dating app where users search over other user profiles for prospective dates. Users also have
a way to indicate they are "not interested" in a specific profile, meaning that profile should be excluded from their
results in future searches. Assuming we index a unique PK identifier with each profile doc, we can represent this
"block list" semantic with a boolean `MUST_NOT` clause term disjunction provided with each query. Where this use-case 
differs from the UNSPSC case is in the relationship of profile IDs to docs. While UNSPSC to doc is a one-to-many 
relationship, profile IDs are a strict 1-to-1 relationship.

Of course, it should be mentioned that both of these problems could be "inverted" if the allow-/deny-lists are static
to the user by pre-computing which users should _not_ see any given document and indexing a list of "blocked users"
for each one. At query-time, we no longer need a set of terms, but just need to pass the user's unique ID. This
approach has some downsides: 1) block-lists can be slow to update depending on the index update pipeline, 2) in cases
like UNSPSC, a single update to a block-list will result in many documents needing to be updated, 3) the index size
grows with-respect-to the number of users/block-lists in the system, which may not be a reasonable scaling factor
depending on what drives the business. For these reasons, we _assume_ it is a reasonable use-case to want to provide
relatively large lists of "term in set" clauses at query-time.

These use-cases can served through at least three different, functionally equivalent, implementations:
1. A standard `BooleanQuery` disjunction clause, which manages a priority queue of individual term postings and does
"doc at a time" scoring. The downside of this approach tends to be the cost overhead of PQ management.
2. A `TermInSetQuery` clause, which fully iterates all term postings up-front and builds an on-heap bitset representing
the result of the disjunction, effectively doing "term at a time" scoring. The downside of this approach is that it
must fully iterate all the postings, and can't take advantage of skipping.
3. A second-phase doc values filter, which requires values to also be indexed in a columnar store DV field and checks
every "candidate" doc in a post-filtering type approach (i.e., `SortedSetDocValuesField#newSlowSetQuery`). The
downside of this approach is that it must check every candidate, and can't take advantage of skipping.

`IndexOrDocValuesQuery` can also be used to "wrap" an "index query" (one of `BooleanQuery` or `TermInSetQuery`) and a
"doc values" query (`SortedSetDocValuesField#newSlowSetQuery`). This query compares the estimated lead cost to the
estimated lead cost of the index query to decide if a posting-approach or a docvalues-approach would be more likely
to perform best.

In an effort to determine the relative performance characteristics of these different approaches, I ran benchmarks over
various different use-cases.

## Setup
Benchmarks were run on an index of "geonames" data (specifically `allCountries.txt` available from the
[geonames download server](https://download.geonames.org/export/dump/)). The index contained 12.3MM documents, with
1) a `name` field that used a standard analyzer to parse the "name" of the entry (`StringField`), 2) an `id` field 
containing the literal ID of the entry (`StringField`), and 3) a `cc` field containing the literal "country code" of 
the entry (`StringField`). The `id` and `cc` were also indexed with doc values (`SortedDocValuesField`). The data was
shuffled prior to indexing since the rows are sorted by country code, which skews results if left sorted.

Tests were broken down into "normal" use-case and PK-specific use-cases. All use-cases added a single required
"term in set" clause to a boolean query; "normal" use-cases used a disjunction of required country codes, while
PK use-cases used a disjunction of unique IDs. (These represent the UNSPSC and dating app examples above).

For each use-case, four different scenarios were run where the "term in set" clause was added to different term queries:
* `Large Lead Terms`: The "term in set" clauses were combined with a single term query where the selected term has
a relatively large number of hits (ranging from 27,000 to 181,000). Queries were sequentially run with all four terms
and the total time to execute all four queries was recorded.
* `Medium Lead Terms`: Same as above, but the four queries ranged from 2,000 to 64,000 hits.
* `Small Lead Terms`: Same as above, but the four queries ranged from 49 to 6,000 hits.
* `No Lead Term`: The "term in set" clause was evaluated on its own, with no other query clauses, to test "bulk scoring".

Tests were also broken down by the number of terms present in the "term in set" clause, along with the number of docs
in the index each term was found in:
* `All Country Code Filter Terms`: The "term in set" clause contained all unique country codes in the data set (254),
and cover all indexed documents.
* `Medium Cardinality + High Cost Country Code Filter Terms`: The "term in set" clause contained 20 unique country
codes. Each code was contained in 105,000 to 2.2MM indexed documents.
* `Low Cardinality + High Cost Country Code Filter Terms`: The "term in set" clause contained 10 unique country codes.
Each code was contained in 243,000 to 2.2MM indexed documents.
* `Medium Cardinality + Low Cost Country Code Filter Terms`: The "term in set" clause contained 20 unique country codes.
Each code was contained in 1 to 229 indexed documents.
* `Low Cardinality + Low Cost Country Code Filter Terms`: The "term in set" clause contained 10 unique country codes.
Each code was contained in 1 to 97 indexed documents.
* `High Cardinality PK Filter Terms`: The "term in set" clause contained 500 PK IDs.
* `Medium Cardinality PK Filter Terms`: The "term in set" clause contained 20 PK IDs.
* `Low Cardinality PK Filter Terms`: The "term in set" clause contained 10 PK IDs.

For every scenario, I compared the three approaches described above (standard `BooleanQuery` clause, `TermInSetQuery`
and a DV approach using `SortedSetDocValuesField#newSlowSetQuery`). I also compared two different `IndexOrDocValue`
approaches, the first using `BooleanQuery` as the index query and the second using `TermInSetQuery` (the doc values
query was `SortedSetDocValuesField#newSlowSetQuery` in both cases).

All benchmarks were run on an AWS ec2 host of type `m5.12xlarge`.

## General Observations
* All approaches require "term seeking." `BooleanQuery` and `TermInSetQuery` must seek to each term in the terms
dictionary to load postings. The DV query has to do a different flavor of "term seeking" during setup when it looks up
the corresponding ordinal for each term.
* DV is always best for small lead terms for the non-PK cases. This logically makes sense since the number of "lead"
docs is the main driver of cost.
* DV is generally pretty hard to beat, even as the number of candidate "lead" terms grows. Cost of the postings have to 
get really low for a postings-based approach to win out. PK cases are the extreme example of this.
* Postings are always better for PK cases.
* BQ performance is significantly worse than TiS in most cases. I found the degree of this different very surprising.
It appears to be due primarily to the overhead of managing all the postings on the PQ.
* BQ and TiS converge on the "low" cardinality cases (and PK cases) since TiS currently rewrites itself to a boolean
query when there are 16 or fewer terms.
* BQ provides a reasonable cost estimation for `IndexOrDV` by summing up the postings lengths for all terms, which are
known since it does all the term seeking work during setup.
* TiS generally provides a gross overestimation of cost since it does not do any term seeking, and must rely on field-
level statistics with some worst case assumptions (essentially that the query terms cover the total length of all
postings for the field). This tends to result in `IndexOrDV` always using doc values. Fortunately, this is _usually_
the optimal decision (but not always).
* TiS provides accurate cost estimation for the PK case, since it can be identified with field-level statistics.

### All Country Code Filter Terms
| Approach      | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|---------------|------------------|-------------------|------------------|---------------|
| BQ            | 1846.75          | 1159.72           | 441.12           | 311.69        |
| TiS           | 215.08           | 211.86            | 210.41           | 128.45        |
| DV            | 28.11            | 21.16             | 17.70            | 153.40        |
| IndexOrDV BQ  | 94.13            | 88.43             | 81.41            | 311.67        |
| IndexOrDV TiS | 28.24            | 21.22             | 17.79            | 117.62        |

* Better to use DV in all cases
* IndexOrDV + TiS correctly uses DV in all cases
* IndexOrDV + BQ also appears to correctly use DV in all cases but is paying significant overhead since TermWeight
  doesn't implement ScoreSupplier (so we term-seek each term and load postings but never use them).


### Medium Cardinality + High Cost Country Code Filter Terms
| Approach      | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|---------------|------------------|-------------------|------------------|---------------|
| BQ            | 437.89           | 206.56            | 66.39            | 94.11         |
| TiS           | 133.76           | 128.72            | 126.86           | 75.41         |
| DV            | 16.41            | 7.25              | 3.00             | 231.94        |
| IndexOrDV BQ  | 21.67            | 12.55             | 8.22             | 94.07         |
| IndexOrDV TiS | 16.44            | 7.29              | 3.03             | 75.41         |

* Better to use DV in all cases
* BQ is better than TiS with small lead terms
* IndexOrDV + TiS correctly uses DV in all cases
* IndexOrDV + BQ also appears to correctly use DV in all cases but is paying significant overhead since TermWeight
  doesn't implement ScoreSupplier (so we term-seek each term and load postings but never use them).


### Low Cardinality + High Cost Country Code Filter Terms
| Approach      | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|---------------|------------------|-------------------|------------------|---------------|
| BQ            | 242.63           | 113.98            | 38.18            | 70.22         |
| TiS           | 242.15           | 113.79            | 38.14            | 70.05         |
| DV            | 16.37            | 6.46              | 2.37             | 256.30        |
| IndexOrDV BQ  | 18.97            | 9.10              | 5.02             | 70.18         |
| IndexOrDV TiS | 18.90            | 9.08              | 4.94             | 70.08         |

* Better to use DV in all cases
* All IndexOrDV approaches use DV in all cases. Note that TiS rewrites to a BQ, so the behavior is the same.
* There's the same up-front setup cost of the BQ mentioned above since TiS rewrites to a BQ in this case.


### Medium Cardinality + Low Cost Country Code Filter Terms
| Approach      | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|---------------|------------------|-------------------|------------------|---------------|
| BQ            | 6.20             | 5.70              | 5.32             | 1.38          |
| TiS           | 4.41             | 3.82              | 3.40             | 0.77          |
| DV            | 12.70            | 5.92              | 2.72             | 124.91        |
| IndexOrDV BQ  | 6.27             | 5.69              | 5.46             | 1.37          |
| IndexOrDV TiS | 12.85            | 5.99              | 2.76             | 0.77          |

* Better to use postings with large/medium lead terms
* Better to use DV with small lead terms
* IndexOrDV + TiS incorrectly uses DV in the large/medium cases due to over-estimated cost
* IndexOrDV + BQ always determines that postings should be better because it has a more accurate cost, which is
  the right decision for large/medium, but wrong for small.


### Low Cardinality + Low Cost Country Code Filter Terms
| Approach      | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|---------------|------------------|-------------------|------------------|---------------|
| BQ            | 3.00             | 2.75              | 2.54             | 0.62          |
| TiS           | 3.00             | 2.80              | 2.52             | 0.62          |
| DV            | 12.14            | 5.31              | 2.10             | 124.10        |
| IndexOrDV BQ  | 3.09             | 2.93              | 2.66             | 0.61          |
| IndexOrDV TiS | 3.09             | 2.95              | 2.61             | 0.62          |

* Better to use postings with large/medium lead terms
* Better to use DV with small lead terms
* All IndexOrDV approaches just end up as BQs since TiS rewrites. Note that this allows TiS to make the correct cost
  decision since it's rewritten to a BQ.


### High Cardinality PK Filter Terms
| Approach      | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|---------------|------------------|-------------------|------------------|---------------|
| BQ            | 87.00            | 86.55             | 85.47            | 21.32         |
| TiS           | 36.14            | 35.85             | 35.39            | 8.72          |
| DV            | 104.31           | 90.60             | 85.71            | 182.16        |
| IndexOrDV BQ  | 87.85            | 87.72             | 86.58            | 21.55         |
| IndexOrDV TiS | 38.54            | 43.83             | 69.31            | 9.04          |

* TiS is far superior in all cases.
* It's surprising BQ performs so poorly. I'm not entirely sure where this cost is coming from yet.
* IndexOrDV + BQ is always using a postings approach, but is suffering from the BQ overhead costs
* IndexOrDV + TiS is _sometimes_ correctly choosing postings but sometimes incorrectly using doc values.


### Medium Cardinality PK Filter Terms
| Approach      | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|---------------|------------------|-------------------|------------------|---------------|
| BQ            | 4.25             | 4.25              | 4.20             | 0.91          |
| TiS           | 2.92             | 3.01              | 2.96             | 0.56          |
| DV            | 17.53            | 8.92              | 5.74             | 124.96        |
| IndexOrDV BQ  | 4.31             | 4.23              | 4.27             | 0.92          |
| IndexOrDV TiS | 3.14             | 3.14              | 3.04             | 0.56          |

* Postings-based approach is better in all cases
* All IndexOrDV approaches appear to correctly use a postings-approach.
* TiS-based outperforms BQ-based, tracking their base performance


### Low Cardinality PK Filter Terms
| Approach      | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|---------------|------------------|-------------------|------------------|---------------|
| BQ            | 2.26             | 2.27              | 2.27             | 0.46          |
| TiS           | 2.29             | 2.31              | 2.30             | 0.47          |
| DV            | 14.74            | 6.83              | 3.90             | 115.87        |
| IndexOrDV BQ  | 2.30             | 2.30              | 2.35             | 0.47          |
| IndexOrDV TiS | 2.32             | 2.33              | 2.33             | 0.47          |

* Postings approach is better in all cases
* Both IndexOrDV approaches are using a postings-based approach. Note that TiS is rewriting to BQ, so their
  behavior is the same.
