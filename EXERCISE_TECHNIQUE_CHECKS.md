# Exercise technique checks

What each rule engine validates per repetition, and the thresholds that
trigger each fault. All angles are degrees; "debounced" means a condition
must hold for 2+ consecutive frames (see `ConsecutiveGate`) before it counts,
so single noisy readings never flip a phase or fail a rep on their own.

| Exercise | Check | What is measured | Threshold |
|---|---|---|---|
| **Squat** | Excessive torso lean | Torso angle from vertical (shoulder-hip) | > 45° |
| | Knees past toes | Normalized knee-to-toe offset (knee/ankle/foot-index) | > 0.2 |
| | Insufficient depth | Knee angle (hip-knee-ankle) never reached required depth | never ≤ 110° |
| **Push-up** | Body not straight | Body-line angle (shoulder-hip-ankle) | outside 170°-190° |
| | Invalid bottom orientation | \|hip-to-ankle tilt from horizontal\|, checked only at the bottom | > 10° |
| | Insufficient depth | Elbow angle (wrist-elbow-shoulder) never reached required depth | never ≤ 95° |
| | Insufficient elbow extension | Elbow reverses (drops back below 110°, the bottom-exit boundary) before ever locking out at the top | never ≥ 150° before reversing |
| **Biceps curl** | Excessive torso movement | Torso angle from vertical | > 15° |
| | Excessive upper arm movement | Upper-arm-to-torso angle (elbow drifting from the body) | > 30° |
| | Incomplete curl | Elbow angle never reached the top of the curl | never ≤ 60° |
| | Incomplete lowering | Elbow reverses (drops back below 80°, the lowering-exit boundary) before ever fully extending back down | never ≥ 130° before reversing |
| **Shoulder press** | Excessive torso lean | Torso angle from vertical | > 15° |
| | Arms not vertical | Shoulder-wrist deviation from vertical, checked only once the elbow is near lockout (≥140°) | > 30° (20-30° tolerated) |
| | Asymmetric arm position | \|left elbow angle - right elbow angle\|, checked only once pressing is underway (elbow > 135°) | > 12° (8-12° tolerated) |
| | Insufficient elbow extension | A sustained press (3+ frames in PRESSING) returns to START without ever reaching lockout | never ≥ 150° |

## Notes

- **Squat**: 2D knee-alignment is implemented but intentionally disabled
  (unreliable outside an exact side-on camera angle). There is no minimum
  torso-lean check -- staying upright the whole rep is valid form.
- **Push-up / Biceps curl "reversal" checks**: a repetition is only counted
  as correct once the user both reaches the far end of the movement *and*
  returns to the start under control. If the user reverses direction and
  starts the next rep before completing that return leg, the abandoned rep
  is immediately closed out as incorrect and the next one starts right away.
  Without this, a user who consistently doesn't lock out / fully extend
  between reps would leave the state machine stuck waiting for a position it
  never reaches, so every subsequent repetition -- correct or not -- would
  silently go uncounted.
- **Squat / Push-up "rep start" thresholds**: the angle that arms a new
  repetition (leaving the rest position) is kept a deliberate 10° below the
  angle that completes one (returning to the rest position). Using the exact
  same value for both let ordinary pose noise while just holding still after
  a real rep arm and immediately complete a second, spurious
  "insufficient depth" repetition -- showing up as two feedback events for
  one physical rep.
- **Shoulder press**: START-position problems (wrong elbow height, arms not
  bent enough) only block a repetition from arming -- they are never scored
  as a fault themselves, the user is just guided back to a valid start.
