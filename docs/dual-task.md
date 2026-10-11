# Motor-cognitive (dual-task) test: protocol, scoring and limits

Algorithm `dual-task-1.0`, scoring `dual-task-score-0.1-preliminary`. Source: `app/src/main/java/com/example/parkinson/dualtask/`.

## Protocol (as shown to the person)

1. Phone in the front trouser pocket, as in the phone walking test (`ImuGaitSession`, 50 Hz accelerometer + gyroscope).
2. Walk 1: 30 s of usual walking.
3. A start number is drawn from 99, 98, 96, 94, 92, 91, shown large and spoken.
4. Walk 2: 30 s of walking while subtracting 3 aloud (99, 96, 93 ...). The spoken clip that starts this walk names the
   start number.
5. A spoken "stop" clip ends the walk. Only then, standing still, the person enters the last number said, or taps
   "I don't remember". Nothing is typed while walking.

The two walks are not stored as walking-test results; one combined `DualTaskResult` is saved (table
`dual_task_assessments`, database v15).

## Answers

- From the entered number: answers = (start − last) / 3, rounded down. When (start − last) is not a multiple of 3,
  the count is kept and marked inexact. A last number above the start is rejected.
- Optional microphone (the person's choice, RECORD_AUDIO): during walk 2 the audio is held in memory only, and
  `SpokenAnswerCounter` keeps two numbers: utterances and long pauses. The first 5 s (the spoken instruction) are
  ignored. Voice activity uses the speech test's detector (10th-percentile noise floor + 10 dB). Gaps under 450 ms
  join one number's words, bursts under 200 ms are dropped, and silences of 3 s or more between answers are long
  pauses. Words are not recognised, so a wrong number counts like a right one.
- Answers per minute use the entered number, or the microphone count when no number was entered.

## Score (0-100, higher = closer to healthy older adults)

| Domain | 100 at | 0 at |
|---|---|---|
| Gait during the dual walk | `GaitScoring.score` of walk 2 (no tremor domain) | |
| Answers per minute | 22 | 4 |
| Cadence cost = 100 × (single − dual) / single | ≤ 10 % | 35 % |

Overall = mean of the available domains, shown only when at least two are available.

Signs (reported, not diagnosed):

- slow answers: under 12 per minute;
- large gait cost: cadence cost of 20 % or more;
- variability rises: stride-time CV rises by 1.5 percentage points or more and the dual-walk CV is above the walking
  test's sign threshold (3.9 %);
- long pauses: 2 or more (microphone only);
- a pattern more common in Parkinson's: at least two of the signs above.

## References and how they set the values

Checked against PubMed on 2026-10-11.

- Srygley JM et al. Brain Res 2009;1253:92-99, [doi:10.1016/j.brainres.2008.11.067](https://doi.org/10.1016/j.brainres.2008.11.067).
  276 healthy adults aged 70-90 subtracting 3 aloud while walking for 2 minutes: gait speed 1.24 → 1.11 m/s (about
  10 % slower; confirmed in the full text). The count of about 45 subtractions in 2 minutes (SD 16.9, about 22 per
  minute) is from the paper's table, which PubMed does not return. This sets the healthy points (22 per minute, 10 %
  cost). The 10 % is a gait-speed cost; cadence usually drops less than speed, so using it for cadence is lenient.
- Priest AW et al. J Neuroeng Rehabil 2008;5:29, [doi:10.1186/1743-0003-5-29](https://doi.org/10.1186/1743-0003-5-29).
  Healthy women aged about 80 counting backwards slowed by 30 % (young women 18 %), and their stride-velocity CV also
  rose (8.2 → 14.9 %). Costs vary widely in healthy people, which is why the large-cost sign starts at 20 % and the
  variability sign also needs the dual-walk CV above 3.9 %.
- Yogev G et al. Eur J Neurosci 2005;22:1248-1256, [doi:10.1111/j.1460-9568.2005.04298.x](https://doi.org/10.1111/j.1460-9568.2005.04298.x)
  (PhysioNet gaitpdb). Gait speed fell in both groups in parallel; gait variability rose with dual tasking only in
  Parkinson's (H&Y 2-3), not in age-matched controls. This is the variability sign.
- Vitorio R et al. J Parkinsons Dis 2021;11:653-664, [doi:10.3233/JPD-202289](https://doi.org/10.3233/JPD-202289).
  144 people with Parkinson's and 79 controls, dual task = reciting every other letter of the alphabet: the
  gait-speed cost discriminated the groups only weakly (AUC 0.54); arm swing and turning costs did better. For this
  reason the score combines several domains and is labelled preliminary.

The 0 points (4 answers per minute, 35 % cost) and the sign thresholds are the app's own choices between the healthy
values and reported Parkinson's values. They are not validated on this app's data.

## Limits

- Phone-in-pocket cadence and stride CV, not a gait lab; speed is not measured.
- Arithmetic skill, education, hearing, anxiety and fatigue change the answers.
- One trial; learning the task changes later results, which is why the start number varies.
- No diagnosis: the result screen says so.
