# Reference values (norms)

The app bundles a small table of published reference values in
`app/src/main/assets/norms/reference_norms.csv`. It works on the phone with no server. The result screens use it
to compare a result with:

- healthy people of the registered age and sex, and
- people with Parkinson's disease.

The registered profile supplies the age, sex and diagnosis. Without a registration, only the values that don't
depend on age are shown.

Every value was found and checked through PubMed on 2026-10-11. Each row of the CSV names its source and DOI.

The comparison is shown next to the existing scores. It does not change any stored score or scoring version. The
studies measured with lab equipment (walkways, optoelectronic systems, Kinect, body-worn sensors), not a phone, so
the comparison is approximate. It is not a diagnosis.

## How a value is judged

The code is in `norms/NormComparator.kt`.

| Situation | Verdict |
|---|---|
| A cut-off is published and the value is past it | Past the cut-off |
| The healthy mean and SD are published | Inside the healthy range, or more than 1.5 SD worse than the healthy mean (the same distance the gait signs use) |
| Only the healthy mean is published (Bohannon) | No worse than average, or worse than average. With a published Parkinson's mean as well (El-Gohary), the verdict says which of the two group means the value is nearer to |
| Only a cut-off is published | Past it or not |
| Tremor frequency | 8–12 Hz is the physiological band. 4–8 Hz is the band of most pathological tremors, which includes Parkinson's rest tremor and essential tremor, so it is not specific to Parkinson's |

When the person has registered a Parkinson's diagnosis, the Parkinson's reference is listed first. The screen also
says whether the value is better or worse than the Parkinson's mean.

## Values used

| Test | Measure | Group | Value | Source |
|---|---|---|---|---|
| Gait | Cadence (steps/min) | Healthy, age 70+ | All 109 (SD 13); men 103; women 112 | Hollman 2011, [doi:10.1016/j.gaitpost.2011.03.024](https://doi.org/10.1016/j.gaitpost.2011.03.024) |
| Gait | Stride-time CV (%) | Cut-off between PD and healthy | 2.4 % (CI 1.9–3.9) | König 2016, [doi:10.3389/fnhum.2016.00319](https://doi.org/10.3389/fnhum.2016.00319) |
| Gait | Turn velocity (deg/s) | Healthy / PD | 78.0 / 69.7 | El-Gohary 2014, [doi:10.3390/s140100356](https://doi.org/10.3390/s140100356). The values sit in a table that PubMed's full text did not include, so they were not re-checked |
| Sit-to-stand (5 times) | Time (s) | Healthy, by age | 60–69: 11.4; 70–79: 12.6; 80–89: 14.8. A slower time is worse than average | Bohannon 2006, [doi:10.2466/pms.103.1.215-222](https://doi.org/10.2466/pms.103.1.215-222) |
| Sit-to-stand (5 times) | Time (s) | PD | Mean 20.25 (SD 14.12). The fall cut-off is 16.0 s | Duncan 2011, [doi:10.1016/j.apmr.2011.04.008](https://doi.org/10.1016/j.apmr.2011.04.008) |
| Finger tapping | Tap rate (Hz) | Healthy / PD off medication | 3.08 (SD 1.09) / 3.24 (SD 0.93). This is 46.26 and 48.65 taps in 15 s | Paparella 2025, [doi:10.1002/mds.70082](https://doi.org/10.1002/mds.70082) |
| Finger tapping | Rhythm CV (%) | Healthy / PD off medication | 9 (SD 3) / 14 (SD 7) | Paparella 2025, [doi:10.1002/mds.70082](https://doi.org/10.1002/mds.70082) |
| Pronation-supination | Cycle rate (Hz) | Cut-off between PD and healthy (Kinect) | 2.36 (sensitivity 62 %, specificity 80 %) | Wu 2023, [doi:10.1177/20552076231176653](https://doi.org/10.1177/20552076231176653) |
| Resting tremor | Frequency (Hz) | Bands | Physiological 8–12; pathological 4–8 | MDS consensus, Bhatia 2018, [doi:10.1002/mds.27121](https://doi.org/10.1002/mds.27121) |

## Kept in the table but not used

| Measure | Why it is not used |
|---|---|
| Wu 2023 tap-rate cut-off, 2.65 Hz | Paparella 2025 found that tap count does not separate PD from healthy people |
| Wu 2023 pronation amplitude cut-off, 94° | Its specificity is only 48 % |
| Wu 2023 hand open/close cut-off, 4.14/s | The paper may count each opening and each closing, while the app counts full cycles |
| Bonacina 2024 cadence in PD | PD cadence is only 4.93 steps/min lower, which does not separate the groups ([doi:10.1016/j.clinbiomech.2024.106359](https://doi.org/10.1016/j.clinbiomech.2024.106359)) |

## Gaps (no usable published reference found yet)

- **Age bands:**
  - Cadence norms cover only age 70+ (Hollman), so no cadence comparison is shown for younger people.
  - Sit-to-stand norms cover only age 60–89 (Bohannon), so no healthy sit-to-stand comparison is shown outside
    that range.
  - Dávila-Oña 2025 gives five-times sit-to-stand values by sex and age from 18 to 80, but they were measured at
    2500 m altitude, so they were not used.
- **Sex:** cadence is the only measure split by sex. Its sex-specific SD was not taken, so the whole sample's SD of
  13 is used for both sexes.
- **Hand stability, hand open/close and speech:** no comparable numbers were found.
  - Speech-rate norms don't transfer to Persian.
  - F0 variability papers (Rusz 2011, Hlavnička 2017) don't give their numbers in the PubMed text.
- **Dual task:** its references stay in `docs/dual-task.md`.

## Measurement differences to keep in mind

- **Sit-to-stand:** the app times from the first stand-up to the last sit-down. The clinical test times from "go".
  The app's time is therefore slightly shorter than a clinic time.
- **Gait:** the studies used walkways or body-worn sensors. The app uses a phone in the pocket and measures thigh
  swing.
