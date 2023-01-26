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

A postings-based approach is better than DV in this case given the PK-nature/low overall cost.
NOCOMMIT: what's happening here?

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 3.88             | 5.67              | 6.02             |
| DV           | 5.83             | 8.42              | 8.81             |
| IndexOrDV    | 7.20             | 10.47             | 11.19            |
| Proposed TiS | 5.14             | 7.83              | 8.71             |


### Medium Cardinality PK Filter Terms
* Term-in-set cardinality: 20 terms
* Term-in-set cost: exactly 1 for each term / total cost of 20 across all terms

NOCOMMIT

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 0.51             | 0.76              | 0.82             |
| DV           | 0.55             | 0.81              | 0.88             |
| IndexOrDV    | 0.57             | 0.84              | 0.91             |
| Proposed TiS | 0.63             | 0.91              | 0.99             |


### Low Cardinality PK Filter Terms
* Term-in-set cardinality: 10 terms
* Term-in-set cost: exactly 1 for each term / total cost of 10 across all terms

NOCOMMIT

| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms |
|--------------|------------------|-------------------|------------------|
| Current TiS  | 0.59             | 0.88              | 0.94             |
| DV           | 0.46             | 0.68              | 0.74             |
| IndexOrDV    | 0.77             | 1.16              | 1.26             |
| Proposed TiS | 0.50             | 0.75              | 0.82             |