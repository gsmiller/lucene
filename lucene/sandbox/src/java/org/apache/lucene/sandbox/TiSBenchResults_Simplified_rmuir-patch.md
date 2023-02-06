Simplified implementation and with rmuir's patch applied to add scoreSupplier to DocValuesTermsQuery.

### All Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 215.72           | 317.66            | 362.58           | 121.63        |
| DV           | 26.32            | 28.84             | 26.09            | 150.73        |
| IndexOrDV    | 26.64            | 29.01             | 26.27            | 121.66        |
| Proposed TiS | 27.15            | 29.22             | 26.25            | 120.70        |

### Medium Cardinality + High Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 134.17           | 193.34            | 220.05           | 77.39         |
| DV           | 15.13            | 8.20              | 4.22             | 222.52        |
| IndexOrDV    | 15.17            | 8.24              | 4.27             | 77.42         |
| Proposed TiS | 15.59            | 8.74              | 4.53             | 77.79         |

### Medium Cardinality + Low Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 4.29             | 5.52              | 5.29             | 0.78          |
| DV           | 11.17            | 6.73              | 3.90             | 76.97         |
| IndexOrDV    | 12.15            | 7.05              | 3.98             | 0.79          |
| Proposed TiS | 4.24             | 5.44              | 4.64             | 0.78          |

### Low Cardinality + High Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 248.17           | 132.94            | 42.32            | 69.50         |
| DV           | 15.34            | 7.17              | 3.25             | 186.56        |
| IndexOrDV    | 18.05            | 11.20             | 7.44             | 69.60         |
| Proposed TiS | 248.21           | 132.94            | 42.24            | 69.55         |

### Low Cardinality + Low Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 3.05             | 4.26              | 4.30             | 0.65          |
| DV           | 11.46            | 6.10              | 3.03             | 81.27         |
| IndexOrDV    | 3.11             | 4.38              | 4.50             | 0.65          |
| Proposed TiS | 3.05             | 4.16              | 4.32             | 0.65          |

### High Cardinality PK Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 36.66            | 54.59             | 56.27            | 8.90          |
| DV           | 102.82           | 131.54            | 130.65           | 153.47        |
| IndexOrDV    | 39.10            | 76.72             | 115.34           | 9.24          |
| Proposed TiS | 37.60            | 76.06             | 112.81           | 8.90          |

### Medium Cardinality PK Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 3.14             | 4.66              | 4.89             | 0.59          |
| DV           | 16.48            | 11.41             | 8.73             | 102.24        |
| IndexOrDV    | 3.22             | 4.66              | 5.37             | 0.59          |
| Proposed TiS | 3.03             | 4.65              | 5.43             | 0.58          |

### Low Cardinality PK Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 2.42             | 3.71              | 3.93             | 0.50          |
| DV           | 13.76            | 8.41              | 5.91             | 94.47         |
| IndexOrDV    | 2.45             | 3.67              | 3.92             | 0.50          |
| Proposed TiS | 2.40             | 3.64              | 3.92             | 0.49          |
