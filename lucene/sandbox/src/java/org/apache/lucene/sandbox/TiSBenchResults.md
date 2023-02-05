### All Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| TiS                | 212.47           | 313.42            | 356.76           | 121.85        |
| DV                 | 27.22            | 29.76             | 26.85            | 151.19        |
| Original IndexOrDV | 27.50            | 30.07             | 27.14            | 122.09        |
| Proposed IndexOrDV | 27.71            | 30.28             | 27.32            | 121.92        |

### Medium Cardinality + High Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| TiS                | 135.22           | 195.28            | 221.91           | 78.37         |
| DV                 | 15.36            | 8.47              | 4.27             | 230.19        |
| Original IndexOrDV | 15.44            | 8.58              | 4.40             | 78.40         |
| Proposed IndexOrDV | 15.57            | 8.79              | 4.60             | 78.41         |

### Medium Cardinality + Low Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| TiS                | 4.25             | 5.43              | 5.12             | 0.77          |
| DV                 | 11.49            | 6.79              | 3.87             | 84.72         |
| Original IndexOrDV | 12.09            | 7.08              | 4.03             | 0.78          |
| Proposed IndexOrDV | 4.33             | 5.59              | 4.92             | 0.78          |

### Low Cardinality + High Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| TiS                | 238.51           | 129.98            | 41.99            | 69.12         |
| DV                 | 15.10            | 7.08              | 3.17             | 195.97        |
| Original IndexOrDV | 17.75            | 11.07             | 7.27             | 69.11         |
| Proposed IndexOrDV | 17.71            | 11.07             | 7.20             | 69.09         |

### Low Cardinality + Low Cost Country Code Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| TiS                | 2.93             | 4.01              | 4.11             | 0.63          |
| DV                 | 11.32            | 6.02              | 2.96             | 83.97         |
| Original IndexOrDV | 2.99             | 4.12              | 4.23             | 0.63          |
| Proposed IndexOrDV | 3.01             | 4.16              | 4.51             | 0.63          |

### High Cardinality PK Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| TiS                | 38.69            | 57.70             | 59.51            | 9.43          |
| DV                 | 103.97           | 132.69            | 131.82           | 152.42        |
| Original IndexOrDV | 41.01            | 79.15             | 116.95           | 9.74          |
| Proposed IndexOrDV | 39.91            | 59.69             | 77.71            | 9.73          |

### Medium Cardinality PK Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| TiS                | 3.07             | 4.62              | 4.98             | 0.59          |
| DV                 | 17.23            | 11.49             | 8.76             | 101.65        |
| Original IndexOrDV | 3.18             | 4.74              | 5.46             | 0.60          |
| Proposed IndexOrDV | 3.19             | 4.89              | 5.16             | 0.60          |

### Low Cardinality PK Filter Terms
| Approach           | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------------|------------------|-------------------|------------------|---------------|
| TiS                | 2.36             | 3.55              | 3.83             | 0.49          |
| DV                 | 14.40            | 8.44              | 5.96             | 93.94         |
| Original IndexOrDV | 2.39             | 3.58              | 3.84             | 0.49          |
| Proposed IndexOrDV | 2.41             | 3.62              | 3.82             | 0.49          |
