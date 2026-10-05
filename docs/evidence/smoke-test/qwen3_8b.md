# Tool-calling smoke test: qwen3:8b

Model: `qwen3:8b` · Temperature: `0`

**Verdict: ADOPT**

| Metric | Value |
|---|---|
| Valid positives (gating, adopt at >= 80%) | 8 of 8 (100.0%) |
| Negatives without a call (gating, >= 1) | 2 of 2 |
| First-attempt argument validity | 8 of 8 (100.0%) |
| Median latency per prompt (plan B above 60.0 s) | 14.6 s |
| Max latency per prompt | 89.6 s |
| Prompts that threw | 0 |

| ID | Kind | Passed | Calls | Expected in answer | Latency | Prompt | Answer | Error |
|---|---|---|---|---|---|---|---|---|
| P1 | positive | yes | `{"zone":"America/Argentina/Buenos_Aires"}` | no | 89.6 s | What time is it in Buenos Aires? | The current time in Buenos Aires is 9:00 AM. |  |
| P2 | positive | yes | `{"zone":"Asia/Tokyo"}` | yes | 12.4 s | What is the current time in Tokyo? | The current time in Tokyo is 21:00 on October 5, 2026. |  |
| P3 | positive | yes | `{"zone":"America/New_York"}` | no | 14.1 s | Tell me the current date and time in New York. | The current date and time in New York is October 5, 2026, at 8:00 AM (EDT). |  |
| P4 | positive | yes | `{"zone":"Asia/Tokyo"}` | yes | 13.6 s | Is it already evening in Tokyo? | Yes, it is already evening in Tokyo. The current time is 21:00 (9:00 PM) in Tokyo time. |  |
| P5 | positive | yes | `{"zone":"Europe/Rome"}` | no | 15.8 s | Would a shop in Rome that opens at 9am be open right now? | The current time in Rome is 2:00 PM. If the shop in Rome opens at 9:00 AM, it would be open right now. |  |
| P6 | positive | yes | `{"zone":"America/Los_Angeles"}` | n/a | 13.6 s | What time is it in Portland? | The current time in Portland, which is in the America/Los_Angeles time zone, is 5:00 AM. |  |
| P7 | positive | yes | `{"zone":"Europe/London"}`<br>`{"zone":"America/Los_Angeles"}` | no | 19.8 s | What time is it in London and in Los Angeles? | The current time in London is 1:00 PM, and in Los Angeles, it is 5:00 AM. |  |
| P8 | positive | yes | `{"zone":"America/New_York"}`<br>`{"zone":"Asia/Tokyo"}` | yes | 17.6 s | How many hours ahead of New York is Tokyo right now? | Tokyo is currently 13 hours ahead of New York. |  |
| N1 | negative | yes | none | yes | 2.6 s | What is the capital of Japan? | The capital of Japan is Tokyo. |  |
| N2 | negative | yes | none | yes | 15.0 s | How many minutes are in a day? | There are 24 hours in a day, and each hour has 60 minutes. Therefore, the total number of minutes in a day is: $$ 24 \text{ hours/day} \times 60 \text{ minutes/hour} = 1440 \text{ minutes/day} $$ So, there are **1440 minutes** in a day. |  |
