

#### count
| Task                | Existing TiSQuery (msec) | IndexOrDocValues (msec) | Proposed TiSQuery (msec) |
|---------------------|--------------------------|-------------------------|--------------------------|
| BIG_BIG             | 59.3                     | 8.3                     | 9.6                      |
| MEDIUM_BIG          | 82.2                     | 3.7                     | 4.2                      |
| SMALL_BIG           | 93.1                     | 1.7                     | 1.8                      |
| BIG_MEDIUM          | 0.7                      | 0.8                     | 0.7                      |
| MEDIUM_MEDIUM       | 1.0                      | 1.1                     | 1.0                      |
| SMALL_MEDIUM        | 1.1                      | 1.2                     | 1.2                      |
| BIG_PK              | 1.3                      | 1.9                     | 0.9                      |
| MEDIUM_PK           | 1.9                      | 2.8                     | 1.7                      |
| SMALL_PK            | 2.4                      | 3.1                     | 1.8                      |
| BIG_BIG_COMBINED    | 59.9                     | 24.9                    | 22.8                     |
| MEDIUM_BIG_COMBINED | 83.1                     | 12.0                    | 11.7                     |
| SMALL_BIG_COMBINED  | 94.0                     | 5.8                     | 6.0                      |


#### search
| Task                | Existing TiSQuery (msec) | IndexOrDocValues (msec) | Proposed TiSQuery (msec) |
|---------------------|--------------------------|-------------------------|--------------------------|
| BIG_BIG             | 54.1                     | 3.4                     | 3.1                      |
| MEDIUM_BIG          | 80.5                     | 2.5                     | 2.4                      |
| SMALL_BIG           | 92.2                     | 2.0                     | 1.9                      |
| BIG_MEDIUM          | 0.6                      | 0.8                     | 0.6                      |
| MEDIUM_MEDIUM       | 0.8                      | 1.1                     | 0.9                      |
| SMALL_MEDIUM        | 0.9                      | 1.2                     | 1.0                      |
| BIG_PK              | 1.1                      | 2.9                     | 0.8                      |
| MEDIUM_PK           | 1.7                      | 4.2                     | 1.5                      |
| SMALL_PK            | 1.8                      | 4.4                     | 1.5                      |
| BIG_BIG_COMBINED    | 54.8                     | 7.2                     | 6.7                      |
| MEDIUM_BIG_COMBINED | 81.4                     | 6.6                     | 6.4                      |
| SMALL_BIG_COMBINED  | 93.9                     | 5.9                     | 5.7                      |

