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
- variability rises: stride-time CV rises by a factor of 1.5 or more and the dual-walk CV is above the walking test's
  sign threshold;
- long pauses: 2 or more (microphone only);
- a pattern more common in Parkinson's: at least two of the signs above.

## References and how they set the values

- Srygley JM et al. Brain Res 2009;1253:92-99. Healthy older adults walking while subtracting 3: about 45
  subtractions in 2 minutes (about 22 per minute), gait speed 1.24 → 1.11 m/s (about 10 % cost). This sets the
  healthy points (22 per minute, 10 % cost).
- Yogev G et al. Eur J Neurosci 2005;22:1248-1256 (PhysioNet gaitpdb). In Parkinson's, stride and swing variability
  rise with a dual task (swing CV 2.7 → 6.5 %); in controls they do not (1.3 → 1.2 %). This is the variability sign.
- Vitorio R et al. J Parkinsons Dis 2021 (doi 10.3233/JPD-202289). Dual-task costs alone discriminate Parkinson's
  only weakly (gait-speed cost AUC 0.54). For this reason the score combines several domains and is labelled
  preliminary.

The 0 points (4 answers per minute, 35 % cost) and the sign thresholds are the app's own choices between the healthy
values and reported Parkinson's values. They are not validated on this app's data.

## Limits

- Phone-in-pocket cadence and stride CV, not a gait lab; speed is not measured.
- Arithmetic skill, education, hearing, anxiety and fatigue change the answers.
- One trial; learning the task changes later results, which is why the start number varies.
- No diagnosis: the result screen says so.
