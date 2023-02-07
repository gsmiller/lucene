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
| BQ                 | 1770.50          | 1343.85           | 514.01           | N/A           |
| TiS                | 221.54           | 326.81            | 373.02           | N/A           |
| DV                 | 27.64            | 29.98             | 27.10            | N/A           |
| IndexOrDV BQ       | 94.23            | 130.51            | 126.11           | N/A           |
| IndexOrDV Original | 27.74            | 30.10             | 27.25            | N/A           |
| IndexOrDV Proposed | 213.91           | 312.81            | 281.34           | N/A           |

* Better to use DV in all cases
* IndexOrDV + TiS correctly uses DV in all cases
* IndexOrDV + BQ appears to be correctly using DV as well, but pays a heavy up-front cost for term-seeking? NOCOMMIT?


### Medium Cardinality + High Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 435.76           | 240.28            | 76.73            | N/A           |
| TiS                | 137.00           | 197.95            | 225.34           | N/A           |
| DV                 | 15.75            | 8.43              | 4.36             | N/A           |
| IndexOrDV BQ       | 21.04            | 16.44             | 12.33            | N/A           |
| IndexOrDV Original | 15.77            | 8.49              | 4.42             | N/A           |
| IndexOrDV Proposed | 135.19           | 195.43            | 216.90           | N/A           |

* Better to use DV in all cases
* BQ is better than TiS with small lead terms
* IndexOrDV + TiS correctly uses DV in all cases
* IndexOrDV + BQ appears to be correctly using DV as well, but pays a heavy up-front cost for term-seeking? NOCOMMIT?


### Low Cardinality + High Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 241.08           | 133.04            | 43.48            | N/A           |
| TiS                | 240.71           | 133.21            | 43.56            | N/A           |
| DV                 | 15.34            | 7.21              | 3.27             | N/A           |
| IndexOrDV BQ       | 17.94            | 11.23             | 7.28             | N/A           |
| IndexOrDV Original | 17.94            | 11.19             | 7.34             | N/A           |
| IndexOrDV Proposed | 17.94            | 11.16             | 7.32             | N/A           |

* Better to use DV in all cases
* All IndexOrDV approaches use DV in all cases. Note that TiS rewrites to a BQ, so the behavior is the same.
* There's some significant overhead in IndexOrDV. Maybe the BQ query setup / term-seeking? NOCOMMIT?


### Medium Cardinality + Low Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 6.11             | 8.21              | 8.28             | N/A           |
| TiS                | 4.30             | 5.40              | 5.17             | N/A           |
| DV                 | 11.80            | 6.93              | 3.99             | N/A           |
| IndexOrDV BQ       | 6.23             | 8.47              | 8.68             | N/A           |
| IndexOrDV Original | 11.87            | 7.03              | 4.06             | N/A           |
| IndexOrDV Proposed | 4.37             | 5.61              | 5.31             | N/A           |

* Better to use postings with large/medium lead terms
* Better to use DV with small lead terms
* IndexOrDV + TiS incorrectly uses DV in the large/medium cases due to over-estimated cost
* IndexOrDV + BQ always determines that postings should be better because it has a more accurate cost, but there is
  overhead in query setup / term-seeking (shows up in large lead terms), and doc values would have actually been
  better in medium/small because of this overhead. NOCOMMIT?


### Low Cardinality + Low Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 3.02             | 4.10              | 4.09             | N/A           |
| TiS                | 3.03             | 4.12              | 4.17             | N/A           |
| DV                 | 11.12            | 6.00              | 3.00             | N/A           |
| IndexOrDV BQ       | 3.10             | 4.22              | 4.31             | N/A           |
| IndexOrDV Original | 3.11             | 4.25              | 4.32             | N/A           |
| IndexOrDV Proposed | 3.11             | 4.24              | 4.32             | N/A           |

* Better to use postings with large/medium lead terms
* Better to use DV with small lead terms
* All IndexOrDV approaches just end up as BQs since TiS rewrites. Note that this allows TiS to make the correct cost
 decision since it's rewritten to a BQ.


### High Cardinality PK Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 97.30            | 145.93            | 148.99           | N/A           |
| TiS                | 38.27            | 57.12             | 59.03            | N/A           |
| DV                 | 103.22           | 133.56            | 132.83           | N/A           |
| IndexOrDV BQ       | 98.66            | 147.70            | 161.48           | N/A           |
| IndexOrDV Original | 40.73            | 79.44             | 117.92           | N/A           |
| IndexOrDV Proposed | 40.83            | 79.56             | 118.04           | N/A           |

* TiS is far superior in all cases.
* It's surprising BQ performs so poorly. Where is the cost coming from? Is it a heap management thing? NOCOMMIT?
* All IndexOrDV approaches appear to be correctly using a postings-approach.
* The BQ version is worse than the TiS version because the underlying BQ approach is worse than the TiS approach.
* The TiS version has some significant overhead on top of TiS alone. Where is this coming from? Do we not have the
  DV opto? NOCOMMIT?


### Medium Cardinality PK Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 4.51             | 6.81              | 7.16             | N/A           |
| TiS                | 3.17             | 4.78              | 5.08             | N/A           |
| DV                 | 16.60            | 11.57             | 8.81             | N/A           |
| IndexOrDV BQ       | 4.54             | 6.89              | 7.19             | N/A           |
| IndexOrDV Original | 3.26             | 4.91              | 5.57             | N/A           |
| IndexOrDV Proposed | 3.30             | 4.98              | 5.58             | N/A           |

* Postings-based approach is better in all cases
* All IndexOrDV approaches appear to correctly use a postings-approach.
* TiS-based outperforms BQ-based, tracking their base performance

### Low Cardinality PK Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| BQ                 | 2.45             | 3.68              | 3.91             | N/A           |
| TiS                | 2.47             | 3.74              | 3.94             | N/A           |
| DV                 | 13.83            | 8.52              | 5.97             | N/A           |
| IndexOrDV BQ       | 2.49             | 3.77              | 3.98             | N/A           |
| IndexOrDV Original | 2.51             | 3.79              | 4.00             | N/A           |
| IndexOrDV Proposed | 2.50             | 3.79              | 4.00             | N/A           |

* Postings approach is better in all cases
* Both IndexOrDV approaches are using a postings-based approach. Note that TiS is rewriting to BQ, so their
  behavior is the same.
