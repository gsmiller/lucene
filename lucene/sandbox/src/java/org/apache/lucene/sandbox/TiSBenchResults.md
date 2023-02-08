### General Questions
* What is the main cost driver for DV? Is it really just a fixed term-seeking cost?
  * No, I don't think so. You see the cost go down as the candidate size shrinks.

### General Observations
* DV is always best for small lead terms for the non-PK cases
* DV is generally pretty hard to beat. Cost of the postings have to get really low. PK cases are the extreme of this.
* Postings are always better for PK cases, except BQ when the term cardinality gets large. Is the overhead of doing heap
  management killing its theoretical gains over DV? NOCOMMIT?

### All Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 1846.75          | 1159.72           | 441.12           | 311.69        |
| TiS                | 215.08           | 211.86            | 210.41           | 128.45        |
| DV                 | 28.11            | 21.16             | 17.70            | 153.40        |
| IndexOrDV BQ       | 94.13            | 88.43             | 81.41            | 311.67        |
| IndexOrDV Original | 28.24            | 21.22             | 17.79            | 117.62        |
| IndexOrDV Proposed | 216.17           | 212.39            | 198.90           | 119.39        |

* Better to use DV in all cases
* IndexOrDV + TiS correctly uses DV in all cases
* IndexOrDV + BQ also appears to correctly use DV in all cases but is paying significant overhead since TermWeight
  doesn't implement ScoreSupplier (so we term-seek each term and load postings but never use them).


### Medium Cardinality + High Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 437.89           | 206.56            | 66.39            | 94.11         |
| TiS                | 133.76           | 128.72            | 126.86           | 75.41         |
| DV                 | 16.41            | 7.25              | 3.00             | 231.94        |
| IndexOrDV BQ       | 21.67            | 12.55             | 8.22             | 94.07         |
| IndexOrDV Original | 16.44            | 7.29              | 3.03             | 75.41         |
| IndexOrDV Proposed | 133.88           | 128.97            | 126.34           | 75.33         |

* Better to use DV in all cases
* BQ is better than TiS with small lead terms
* IndexOrDV + TiS correctly uses DV in all cases
* IndexOrDV + BQ also appears to correctly use DV in all cases but is paying significant overhead since TermWeight
  doesn't implement ScoreSupplier (so we term-seek each term and load postings but never use them).


### Low Cardinality + High Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 242.63           | 113.98            | 38.18            | 70.22         |
| TiS                | 242.15           | 113.79            | 38.14            | 70.05         |
| DV                 | 16.37            | 6.46              | 2.37             | 256.30        |
| IndexOrDV BQ       | 18.97            | 9.10              | 5.02             | 70.18         |
| IndexOrDV Original | 18.90            | 9.08              | 4.94             | 70.08         |
| IndexOrDV Proposed | 18.95            | 9.12              | 4.96             | 70.03         |

* Better to use DV in all cases
* All IndexOrDV approaches use DV in all cases. Note that TiS rewrites to a BQ, so the behavior is the same.
* There's the same up-front setup cost of the BQ mentioned above since TiS rewrites to a BQ in this case.


### Medium Cardinality + Low Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 6.20             | 5.70              | 5.32             | 1.38          |
| TiS                | 4.41             | 3.82              | 3.40             | 0.77          |
| DV                 | 12.70            | 5.92              | 2.72             | 124.91        |
| IndexOrDV BQ       | 6.27             | 5.69              | 5.46             | 1.37          |
| IndexOrDV Original | 12.85            | 5.99              | 2.76             | 0.77          |
| IndexOrDV Proposed | 4.56             | 3.98              | 3.54             | 0.78          |

* Better to use postings with large/medium lead terms
* Better to use DV with small lead terms
* IndexOrDV + TiS incorrectly uses DV in the large/medium cases due to over-estimated cost
* IndexOrDV + BQ always determines that postings should be better because it has a more accurate cost, which is
  the right decision for large/medium, but wrong for small.


### Low Cardinality + Low Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 3.00             | 2.75              | 2.54             | 0.62          |
| TiS                | 3.00             | 2.80              | 2.52             | 0.62          |
| DV                 | 12.14            | 5.31              | 2.10             | 124.10        |
| IndexOrDV BQ       | 3.09             | 2.93              | 2.66             | 0.61          |
| IndexOrDV Original | 3.09             | 2.95              | 2.61             | 0.62          |
| IndexOrDV Proposed | 3.06             | 2.94              | 2.64             | 0.62          |

* Better to use postings with large/medium lead terms
* Better to use DV with small lead terms
* All IndexOrDV approaches just end up as BQs since TiS rewrites. Note that this allows TiS to make the correct cost
  decision since it's rewritten to a BQ.


### High Cardinality PK Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 87.00            | 86.55             | 85.47            | 21.32         |
| TiS                | 36.14            | 35.85             | 35.39            | 8.72          |
| DV                 | 104.31           | 90.60             | 85.71            | 182.16        |
| IndexOrDV BQ       | 87.85            | 87.72             | 86.58            | 21.55         |
| IndexOrDV Original | 38.54            | 43.83             | 69.31            | 9.04          |
| IndexOrDV Proposed | 38.38            | 43.86             | 69.36            | 9.05          |

* TiS is far superior in all cases.
* It's surprising BQ performs so poorly. Where is the cost coming from? Is it a heap management thing? NOCOMMIT?
* IndexOrDV + BQ is always using a postings approach, but is suffering from the BQ overhead costs
* IndexOrDV + TiS is _sometimes_ correctly choosing postings but sometimes using doc values. This is surprising. NOCOMMIT?


### Medium Cardinality PK Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 4.25             | 4.25              | 4.20             | 0.91          |
| TiS                | 2.92             | 3.01              | 2.96             | 0.56          |
| DV                 | 17.53            | 8.92              | 5.74             | 124.96        |
| IndexOrDV BQ       | 4.31             | 4.23              | 4.27             | 0.92          |
| IndexOrDV Original | 3.14             | 3.14              | 3.04             | 0.56          |
| IndexOrDV Proposed | 3.15             | 3.14              | 3.12             | 0.58          |

* Postings-based approach is better in all cases
* All IndexOrDV approaches appear to correctly use a postings-approach.
* TiS-based outperforms BQ-based, tracking their base performance


### Low Cardinality PK Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 2.26             | 2.27              | 2.27             | 0.46          |
| TiS                | 2.29             | 2.31              | 2.30             | 0.47          |
| DV                 | 14.74            | 6.83              | 3.90             | 115.87        |
| IndexOrDV BQ       | 2.30             | 2.30              | 2.35             | 0.47          |
| IndexOrDV Original | 2.32             | 2.33              | 2.33             | 0.47          |
| IndexOrDV Proposed | 2.32             | 2.34              | 2.39             | 0.47          |

* Postings approach is better in all cases
* Both IndexOrDV approaches are using a postings-based approach. Note that TiS is rewriting to BQ, so their
  behavior is the same.
