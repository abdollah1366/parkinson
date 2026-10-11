# Walking test: measures, reference values and the preliminary overall score

Scoring version `gait-score-0.1-preliminary`. Feature algorithm `imu-algo-1.0.0+gait-features-1.0`.
Code: `imu/GaitFeatures.kt` (measures), `imu/GaitScoring.kt` (reference values, score, signs).

**Status: preliminary.** The reference values come from published studies of real walkers that used other sensors
(foot switches, pressure mats, a lumbar sensor). They were not measured with a phone in a trouser pocket, and this
method has not been validated against them. The score and the signs are a screening aid only; they are not a
diagnosis. Results recorded before this version keep no score (`ImuGaitResult.gaitScore` is null).

## Measures (phone in the front trouser pocket)

| Measure | How it is computed | Why |
|---|---|---|
| Stride time, cadence | Peaks of the thigh swing: the rotation rate about the gyroscope's dominant axis during straight walking, low-passed at 4 Hz. One peak per stride (two steps). Cadence = 120 000 / stride time (ms). Intervals outside 0.7-2.0 s or longer than 1.6 x the median (a missed swing) are dropped. | The heel-strike peaks of the leg without the phone are weaker and are often missed, which halves the counted steps and inflates the step-interval variability. The thigh swing of the pocket leg is seen in every stride. |
| Stride time variability | Coefficient of variation of the kept stride intervals (at least 5). | Comparable with the stride-time CV of the gait literature. |
| Step timing asymmetry | Autocorrelation of the first difference of the vertical acceleration: the step lag (first peak) and the stride lag (second peak). Asymmetry = 100 x \|2 x step lag / stride lag - 1\|, so equal steps give 0 %. | Timing, not amplitude, so the louder heel strike of the pocket leg does not count as asymmetry. Moe-Nilssen and Helbostad (J Biomech 2004) introduced the step and stride autocorrelation. |
| Step and stride regularity | The autocorrelation peak heights (stored, shown for information). | Same method. |
| Turns | Vertical rotation rate (the gyroscope projected on gravity), zero-phase low-pass 1.5 Hz. A turn peaks at 15 deg/s or more, its bounds are where the rate falls under 5 deg/s, it lasts 0.5-10 s and turns more than 45 degrees. Speed is compared for turns of 90 degrees or more. | The detection parameters of El-Gohary et al. (Sensors 2014). |
| Tremor (experimental) | Welch spectrum (256-point Hann windows) of the three gyroscope axes. The largest peak in 3.5-7.5 Hz while walking (bins within 0.3 Hz of a stride harmonic are skipped), and in 3.5-18 Hz while standing still during the calibration, divided by the median power of the 1-20 Hz bins (at least the power of a 1 deg/s sinusoid, the sensor noise). | Parkinsonian rest tremor is typically 4-6 Hz. No phone-in-pocket reference exists, so tremor is a sign only and never enters the score. |

## Reference values and score bounds

Checked against PubMed on 2026-10-11 (see "Sources checked" at the end).

Each domain is scored linearly between a healthy bound (100) and a zero bound (0). The overall score is the mean of
the measured domains and needs at least two.

| Domain | Reference | Full score | Sign when | Zero |
|---|---|---|---|---|
| Rhythm (cadence) | Healthy adults aged 70+ walk at 109 (SD 13) steps/min; men 103, women 112 (Hollman et al., Gait Posture 2011, 34:111, 294 people, GAITRite). Zero is the mean - 3 SD. | >= 100 steps/min | < 90 (about mean - 1.5 SD) | <= 70 |
| Variability (stride-time CV) | Meta-analysis of 34 studies; the 21 that reported stride-time CV (519 people with PD, 574 controls) give 2.4 % (95 % CI 1.9-3.9 %) as the cut-off that separates the groups, AUC 0.74 (König et al., Front Hum Neurosci 2016, 10:319). | <= 2.4 % | > 3.9 % (upper CI bound) | >= 8 % |
| Symmetry (step timing) | Swing-time asymmetry, 100 x \|ln(right/left)\|, is higher in PD and in elderly fallers than in healthy older adults (Yogev et al., Exp Brain Res 2007, 177:336). This app's step-timing measure is about half that log-ratio for small differences. Bounds are engineering choices aimed at a limp. | <= 5 % | > 10 % | >= 20 % |
| Turning (mean turn velocity, turns >= 90 deg) | Laboratory turns at the preferred speed, lumbar sensor, 21 PD and 19 controls: controls 78.0 deg/s (CV 0.15), PD 69.7 deg/s (CV 0.18); PD turned slower at every speed (El-Gohary et al., Sensors 2014, 14:356). | >= 78 deg/s | < 66 deg/s (control mean - 1 SD) | <= 40 deg/s |

Signs reported (`GaitSign`):

* `LOW_CADENCE`, `HIGH_VARIABILITY`, `ASYMMETRY` (limping or a problem in one leg), `SLOW_TURNING`.
* `WALKING_TREMOR`, `STANDING_TREMOR`: a tremor-band peak at least 8 times the spectral floor (experimental).
* `PARKINSONIAN_PATTERN`: high variability with at least one other PD-associated sign (low cadence, slow turning,
  tremor), or any three of them. The text says these signs are reported more often in people with PD, that it is not
  a diagnosis, and recommends a neurologist.

An asymmetry alone (a limp) does not produce the Parkinsonian pattern.

## Limits

* The literature values were measured with other sensors and protocols; the bounds need re-calibration with phone
  data from people with and without PD (for example the PhysioNet "Gait in Parkinson's Disease" database for stride
  timing, and a thigh-accelerometer dataset such as Daphnet for the pocket position).
* Age is not entered, so age-specific cadence and variability norms are not applied.
* Cadence alone separates PD poorly: meta-analyses found it slightly lower in PD (-4.9 steps/min, Bonacina et al.,
  Clin Biomech 2024, 120:106359) or even higher on medication (Zanardi et al., Sci Rep 2021, 11:752); the main PD
  change is shorter strides, which a phone in the pocket does not measure. A low cadence therefore counts towards the
  Parkinsonian pattern only below 90 steps/min and only together with other signs.
* The walk is 30 s in a home setting; few strides or few large turns leave domains unmeasured (shown as such).
* The tests use synthetic signals (`SyntheticThighGait`), which show the computations are correct, not that real
  Parkinsonian walking is classified correctly.

## Sources checked (PubMed, 2026-10-11)

| Source | What was checked | Result |
|---|---|---|
| König 2016, [doi:10.3389/fnhum.2016.00319](https://doi.org/10.3389/fnhum.2016.00319) | Full text | 2.4 % (95 % CI 1.9-3.9 %), 21 studies, 519 PD / 574 controls: confirmed. |
| El-Gohary 2014, [doi:10.3390/s140100356](https://doi.org/10.3390/s140100356) | Full text | Turn detection parameters and "PD slower at all speeds" confirmed. The 78.0 / 69.7 deg/s values are in a table that PubMed does not return; not re-checked. |
| Yogev 2007, [doi:10.1007/s00221-006-0676-3](https://doi.org/10.1007/s00221-006-0676-3) | Abstract | Asymmetry is higher in PD and fallers, and is defined on swing time as a log-ratio (see table). |
| Tudor-Locke 2019, [doi:10.1186/s12966-019-0769-6](https://doi.org/10.1186/s12966-019-0769-6) | Abstract | Not a source for usual cadence: 100 steps/min is the moderate-intensity threshold for adults aged 21-40. Replaced by Hollman 2011. |
| Hollman 2011, [doi:10.1016/j.gaitpost.2011.03.024](https://doi.org/10.1016/j.gaitpost.2011.03.024) | Full text | 109 (SD 13) steps/min, men 103, women 112, age 70+. |
| Zanardi 2021, [doi:10.1038/s41598-020-80768-2](https://doi.org/10.1038/s41598-020-80768-2); Bonacina 2024, [doi:10.1016/j.clinbiomech.2024.106359](https://doi.org/10.1016/j.clinbiomech.2024.106359) | Abstracts | Cadence in PD vs controls is not consistently lower. |
