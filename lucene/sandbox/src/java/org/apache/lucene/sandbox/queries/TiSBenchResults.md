

#### count
| Task                | Existing TiSQuery (msec) | Existing DVQuery (msec) | IndexOrDocValues (msec) | Proposed TiSQuery (msec) |
|---------------------|--------------------------|-------------------------|-------------------------|--------------------------|
| BIG_BIG             | 59.3                     | 8.2                     | 8.3                     | 11.6                     |
| MEDIUM_BIG          | 82.2                     | 3.7                     | 3.7                     | 5.8                      |
| SMALL_BIG           | 93.1                     | 1.7                     | 1.7                     | 2.6                      |
| BIG_MEDIUM          | 1.1                      | 6.9                     | 7.0                     | 1.1                      |
| MEDIUM_MEDIUM       | 1.6                      | 3.1                     | 3.1                     | 1.5                      |
| SMALL_MEDIUM        | 1.7                      | 1.5                     | 1.5                     | 1.6                      |
| BIG_PK              | 1.3                      | 9.2                     | 1.9                     | 0.9                      |
| MEDIUM_PK           | 1.9                      | 5.4                     | 3.0                     | 1.7                      |
| SMALL_PK            | 2.4                      | 3.6                     | 3.2                     | 1.8                      |
| BIG_BIG_COMBINED    | 59.9                     | 24.3                    | 24.9                    | 20.7                     |
| MEDIUM_BIG_COMBINED | 83.1                     | 11.8                    | 12.0                    | 11.0                     |
| SMALL_BIG_COMBINED  | 94.0                     | 5.8                     | 5.8                     | 5.8                      |


#### search
| Task                | Existing TiSQuery (msec) | Existing DVQuery (msec) | IndexOrDocValues (msec) | Proposed TiSQuery (msec) |
|---------------------|--------------------------|-------------------------|-------------------------|--------------------------|
| BIG_BIG             | 54.1                     | 3.4                     | 3.4                     | 3.8                      |
| MEDIUM_BIG          | 80.5                     | 2.5                     | 2.5                     | 3.1                      |
| SMALL_BIG           | 92.2                     | 2.0                     | 2.0                     | 2.5                      |
| BIG_MEDIUM          | 1.0                      | 14.2                    | 14.3                    | 1.0                      |
| MEDIUM_MEDIUM       | 1.5                      | 5.3                     | 5.4                     | 1.4                      |
| SMALL_MEDIUM        | 1.5                      | 1.7                     | 1.7                     | 1.4                      |
| BIG_PK              | 1.1                      | 16.8                    | 2.9                     | 0.8                      |
| MEDIUM_PK           | 1.7                      | 7.7                     | 4.2                     | 1.5                      |
| SMALL_PK            | 1.8                      | 3.8                     | 4.4                     | 1.5                      |
| BIG_BIG_COMBINED    | 54.8                     | 7.2                     | 7.2                     | 6.7                      |
| MEDIUM_BIG_COMBINED | 81.4                     | 6.7                     | 6.6                     | 6.4                      |
| SMALL_BIG_COMBINED  | 93.9                     | 5.9                     | 5.9                     | 5.7                      |


