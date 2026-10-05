# Tool-calling smoke test: qwen3.5:9b

Model: `qwen3.5:9b` · Temperature: `0`

**Verdict: ADOPT**

| Metric | Value |
|---|---|
| Valid positives (gating, adopt at >= 80%) | 8 of 8 (100.0%) |
| Negatives without a call (gating, >= 1) | 2 of 2 |
| First-attempt argument validity | 8 of 8 (100.0%) |
| Median latency per prompt (plan B above 60.0 s) | 34.9 s |
| Max latency per prompt | 76.5 s |
| Prompts that threw | 0 |

| ID | Kind | Passed | Calls | Expected in answer | Latency | Prompt | Answer | Error |
|---|---|---|---|---|---|---|---|---|
| P1 | positive | yes | `{"zone":"America/Argentina/Buenos_Aires"}` | yes | 76.5 s | What time is it in Buenos Aires? | It is currently 09:00 AM on October 5, 2026 in Buenos Aires. |  |
| P2 | positive | yes | `{"zone":"Asia/Tokyo"}` | yes | 33.9 s | What is the current time in Tokyo? | The current time in Tokyo is 21:00 on October 5, 2026. |  |
| P3 | positive | yes | `{"zone":"America/New_York"}` | no | 34.3 s | Tell me the current date and time in New York. | The current date and time in New York is October 5, 2026 at 08:00 AM EDT. |  |
| P4 | positive | yes | `{"zone":"Asia/Tokyo"}` | yes | 32.7 s | Is it already evening in Tokyo? | Yes, it is evening in Tokyo. The current time there is 9:00 PM on October 5th, 2026. |  |
| P5 | positive | yes | `{"zone":"Europe/Rome"}` | yes | 35.6 s | Would a shop in Rome that opens at 9am be open right now? | Yes, the shop would be open. It is currently 2:00 PM in Rome (Europe/Rome), which is well past its 9 AM opening time and before typical closing hours for shops on a Tuesday. |  |
| P6 | positive | yes | `{"zone":"America/Los_Angeles"}` | n/a | 31.8 s | What time is it in Portland? | It is currently 5:00 AM on October 5, 2026 in Portland. |  |
| P7 | positive | yes | `{"zone":"Europe/London"}`<br>`{"zone":"America/Los_Angeles"}` | no | 41.2 s | What time is it in London and in Los Angeles? | It is currently **1:00 PM** in London and **5:00 AM** in Los Angeles. |  |
| P8 | positive | yes | `{"zone":"America/New_York"}`<br>`{"zone":"Asia/Tokyo"}` | yes | 50.7 s | How many hours ahead of New York is Tokyo right now? | Tokyo is currently **13 hours** ahead of New York. *   Current time in New York (EDT): 8:00 AM on October 5th, UTC-4 *   Current time in Tokyo (JST): 9:00 PM on October 5th, UTC+9 |  |
| N1 | negative | yes | none | yes | 35.9 s | What is the capital of Japan? | The capital of Japan is Tokyo. It has been the de facto capital since 1869 when Emperor Meiji moved his residence there from Kyoto, and it was officially designated as the national capital in 1947 following World War II. Located on Honshu island, Tokyo serves as the country's political, economic, cultural, and educational center. |  |
| N2 | negative | yes | none | yes | 30.0 s | How many minutes are in a day? | There are 1440 minutes in a standard day. This is calculated by multiplying the number of hours in a day (24) by the number of minutes in an hour (60): $24 \times 60 = 1440$. |  |
