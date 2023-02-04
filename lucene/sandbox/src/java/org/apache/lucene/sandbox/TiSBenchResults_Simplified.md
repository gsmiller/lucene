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

### All Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 377.50           | 560.70            | 641.95           | 160.94        |
| DV           | 27.13            | 29.66             | 26.73            | 164.93        |
| IndexOrDV    | 27.39            | 29.92             | 26.95            | 160.99        |
| Proposed TiS | 27.33            | 29.71             | 26.71            | 118.65        |

### Medium Cardinality + High Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 175.48           | 255.43            | 291.31           | 87.03         |
| DV           | 15.00            | 8.37              | 4.33             | 233.31        |
| IndexOrDV    | 15.02            | 8.42              | 4.37             | 87.08         |
| Proposed TiS | 15.79            | 8.81              | 4.58             | 76.84         |

### Medium Cardinality + Low Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 4.31             | 5.54              | 5.34             | 0.79          |
| DV           | 10.52            | 6.62              | 3.94             | 96.89         |
| IndexOrDV    | 10.40            | 6.65              | 3.99             | 0.79          |
| Proposed TiS | 4.29             | 5.46              | 4.71             | 0.78          |

### Low Cardinality + High Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 232.72           | 128.89            | 42.77            | 69.63         |
| DV           | 12.62            | 6.44              | 3.13             | 200.74        |
| IndexOrDV    | 15.35            | 10.68             | 7.40             | 69.61         |
| Proposed TiS | 232.41           | 128.82            | 42.78            | 69.60         |

### Low Cardinality + Low Cost Country Code Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 3.04             | 4.26              | 4.37             | 0.66          |
| DV           | 9.64             | 5.58              | 2.93             | 96.04         |
| IndexOrDV    | 4.09             | 5.72              | 5.83             | 0.66          |
| Proposed TiS | 3.04             | 4.24              | 4.34             | 0.65          |

### High Cardinality PK Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 37.10            | 55.38             | 57.23            | 9.01          |
| DV           | 101.28           | 131.99            | 131.54           | 158.31        |
| IndexOrDV    | 119.70           | 167.19            | 145.77           | 9.34          |
| Proposed TiS | 38.00            | 76.88             | 113.77           | 9.01          |

### Medium Cardinality PK Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 3.13             | 4.70              | 4.92             | 0.59          |
| DV           | 15.50            | 10.87             | 8.56             | 106.64        |
| IndexOrDV    | 6.15             | 9.28              | 9.95             | 0.59          |
| Proposed TiS | 3.12             | 4.64              | 5.44             | 0.61          |

### Low Cardinality PK Filter Terms
| Approach     | Large Lead Terms | Medium Lead Terms | Small Lead Terms | No Lead Terms |
|--------------|------------------|-------------------|------------------|---------------|
| Current TiS  | 2.50             | 3.82              | 4.09             | 0.51          |
| DV           | 12.83            | 7.90              | 5.70             | 98.72         |
| IndexOrDV    | 3.53             | 5.42              | 5.89             | 0.52          |
| Proposed TiS | 2.51             | 3.84              | 4.10             | 0.51          |
