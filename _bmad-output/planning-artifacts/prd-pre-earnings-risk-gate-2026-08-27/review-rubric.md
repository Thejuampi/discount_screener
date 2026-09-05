# PRD Quality Review — Pre-Earnings Risk Gate

Scope of this pass: the whole PRD, weighted toward the Update that added §4.7 "Lectura por ticker", the §2 Alcance bullet, and the "### Lectura por ticker (§4.7)" wiring table in §13. Calibrated to a solo Android app, one reader, no store release: rigor light, substance bar full.

## Overall verdict

The PRD holds up as a working engineering document. Its thesis — valuation and event risk are decided separately, and the event log is the piece that starts paying the day it is written — is stated, defended, and visible in the feature order. §4.7 is the strongest-written section in the document: it names the failure it prevents, states what the filter may never hide, and enumerates the three no-event reasons with one distinguishing which is a real fault. What is at risk is the document's account of itself: §10 "Estado del repo" still says the option chain and the event log do not exist while §13 lists both as built and tested, the header still says "no scoped, no wave assigned" over 20 shipped files, and a backup/restore surface that ships in the same screen as the §4.7 search is in the memlog as a decision but nowhere in the PRD. A reader who trusts §10 and the header would rebuild what already runs.

## Decision-readiness — adequate

Decisions are stated as decisions, not laundered into considerations. §3 puts the whole thesis in three sentences and §4.6 names what was given up ("Vender un call limita el upside que la posición barata busca capturar"). §11 makes the sharpest call in the document: the log moves from last step to first, with the reason ("cada reporte que pasa sin log es un evento que no vuelve"). §13's `UPDATE` vs `KEEP` note and the `filesDir` vs `cacheDir` note are trade-offs with the loser named. §4.7 continues this: the read-only rule is justified by a cost ("gastaría el pedido que el worker necesita y podría quemar la única pasada en rueda del día") instead of being asserted as hygiene.

What weakens the dimension is §12. Of three numbered Open Questions, one is "**Hecho.**", one is struck through as "**Resuelto.**", and a third is struck through as "**Resuelto.**" — leaving exactly one genuinely open item (#2, the power of the §9 criterion), buried under three answered ones. The rubric calls this out by name: questions with the answer in the next sentence are not open questions. They are a changelog wearing an Open Questions heading.

Second, the header contradicts the body. "Status: proposed, 2026-08-27 … Not planned, not scoped, no wave assigned" sits above a §13 that documents 20 built files, 336 tests, and a WorkManager job running every 90 minutes on the phone. A decision-maker cannot tell from the top of this PRD what state the work is in.

### Findings
- **high** Header status contradicts the built state (header, vs §13) — "Status: proposed … Not planned, not scoped, no wave assigned" describes a document whose §13 lists a shipped, tested, wired module including §4.7. *Fix:* change the status line to name the built/pending split, e.g. "Construido §4.1, 4.3, 4.5–4.7 y §7; pendiente §4.2 y §6".
- **medium** §12 is a resolution log, not open questions (§12) — two of three entries are struck through and a third is "Hecho"; only #2 is open. *Fix:* move the resolved items to a short "Decisiones cerradas" list (or leave them in §13, where they already live) and leave §12 with the one live question.
- **low** §12 numbering starts at 0 (§12) — item 0 "Beta ex-evento" breaks the 1..n sequence and reads as an afterthought inserted at the top. *Fix:* renumber.

## Substance over theater — strong

Almost nothing here is furniture. There are no personas, no differentiation section, no vision paragraph — correct for a one-reader tool, and the rubric's shape guidance backs the omission. The NFR-shaped material is product-specific and bounded: 16–20 trimestres with an 80–120 event sector prior, 1.5–2% and 1% cost caps, 50% quote-width cutoff, 60 paired days for beta, 30% floor on the event share of the straddle. These are thresholds someone had to think about, not "the system must be reliable".

§4.7 earns its place the same way. Its opening paragraph is the rare kind of justification that names the actual break — "el evento del ticker que está mirando en el detalle no aparece en la pantalla donde lo está mirando" — and the "Lo que el filtro no puede tapar" block is a designed invariant, not decoration: a filter that hid `damagedLines` and `lastCapture` "convierte una captura rota en una búsqueda sin resultados". That sentence is worth more than most acceptance criteria.

The one soft spot is §9 Criterios de éxito, which was not touched by the Update. Its three bullets are all about the regression and calibration path; none of them says what success looks like for the two surfaces §4.7 just added. Since §4.7 is now in §2 Alcance, the success section no longer covers the scope section.

### Findings
- **medium** §9 does not cover the §4.7 scope (§9 vs §2, §4.7) — the Update added a scope bullet and a feature section but left the success criteria unchanged, so the newest shipped work has no stated bar. *Fix:* add one operational criterion, e.g. "Con la bitácora cargada, el lector llega al evento de un ticker sin recorrer la lista, y ninguna superficie de lectura gasta un pedido de cadena."

## Strategic coherence — strong

There is a thesis and the feature order follows it. §3 states the independence of valuation and event risk; §4.5's matrix is that thesis in table form; §4.6 falls out of the "barato" cell; §11's reordering ("el log deja de ser el último paso y pasa a ser el primero") is derived from the thesis about non-republishable option chains, not from what was easy. §8 keeps the honest limits attached.

§4.7 fits the arc rather than extending it: it is a read path over the log the thesis already made first, and it explicitly refuses to become a second capture path. That refusal is what keeps it coherent — a search box that fetched a chain would have contradicted §11's core argument.

The one coherence break is between §10 and §13. §10 "No existe todavía" lists "Cadena de opciones. No hay ningún cliente de opciones en el repo. Sin esto no hay §4.1 ni §4.3" and "Log de eventos (§7)". §13 lists `YahooOptionChain.kt`, `ImpliedMove.kt` and `EarningsEventLog.kt` as built with fixtures and tests. §10 carries a date stamp (2026-08-27) which softens it, but the document was updated 2026-08-28 and the two sections now assert opposite facts about the same files. On a brownfield PRD the rubric treats existing-code accuracy as load-bearing.

### Findings
- **high** §10 and §13 contradict each other on what exists (§10 "No existe todavía" vs §13) — the option chain client, the event log, and the settlement harness are listed as missing in §10 and as built in §13. *Fix:* reduce §10 to what it still uniquely says (the SUE-without-standard-deviation gap, the `0q` period that was being discarded) and point the rest at §13, or mark §10 explicitly as a frozen snapshot superseded by §13.

## Done-ness clarity — adequate

The mathematical sections are unusually testable for a PRD: §4.1 gives the formula and the strike rule, §4.3 gives three numeric bands, §4.4 gives a full if-then with the trigger, the action, and the audit requirement. §13's sub-sections carry acceptance detail that a story could lift verbatim (the three shapes of `fetchOptionChain` response; the 7-day 8-K proximity rule; the 30% floor).

§4.7 is testable line by line: prefix match, case-insensitive, empty field means full list, both sections filtered, `damagedLines` and `lastCapture` never filtered, no-match text names the term and differs from the empty-install text, detail shows the next report and the last settled one. The repo confirms each of those is a real assertion (`EarningsGatePresentationTest`, `DetailEarningsSectionTest`).

Two gaps remain, one old and one new.

The old one is §4.5: §4.3 defines three risk categories — alto, bajo, normal — and the decision matrix has rows only for Alto and Normal. What the matrix does with "riesgo bajo" is nowhere stated. `DecisionMatrix.kt` classifies the band, so the code has to do something with it; the PRD does not say what.

The new one is §4.7's absence rules. They are written as if the ticker has either one event or none. In the shipped behaviour the absence line appears only when the ticker has no event at all, so a ticker with a settled event but no upcoming one shows the card and no explanation of the missing next report. That is a defensible choice, but the PRD does not make it.

§4.6's put cap is stated as a range — "si cuesta más del 1.5–2% del valor de la posición" — which is not a threshold anyone can implement without picking a number. §13 only documents the 1% put-spread rule, so the ambiguity is currently unresolved in both places.

### Findings
- **high** "Riesgo bajo" has no row in the decision matrix (§4.3 vs §4.5) — three categories are defined, two are decided. *Fix:* add the bajo row (or state in §4.3 that bajo and normal share the same action and drop the third band from the matrix's vocabulary).
- **medium** Protective-put cost cap is a range, not a threshold (§4.6) — "más del 1.5–2%" cannot be evaluated. *Fix:* fix one number, as §4.6 already does for the put spread at 1%.
- **medium** §4.7 does not say what happens when a ticker has some events but not all (§4.7, "Sin evento") — the absence line rules assume zero events; the mixed case (settled present, upcoming missing) is unspecified. *Fix:* one sentence: "La línea de ausencia aparece solo cuando el ticker no tiene ningún evento en la bitácora."
- **low** §4.7 does not place the detail section (§4.7 vs §13 wiring table) — the body says "en el detalle del ticker"; only the wiring table says Snapshot subtab, below the score header. *Fix:* move the placement into §4.7, or accept the table as the sole source and say so.

## Scope honesty — adequate

Non-goals are explicit and doing real work: §2's two exclusions defend the thesis rather than trimming a backlog, and §4.7's read-only rule is a non-goal with the reason attached. §8 keeps live limits in the document instead of retiring them once fixed — the once-a-day queue limit and the "ticker sin archivo en EDGAR conserva la fecha del calendario" caveat are both admissions the PRD did not have to make. §13 closes with "**Falta:** la regresión de §4.2 espera a que la bitácora junte 16–20 trimestres", which is the right kind of honest tail.

There are no `[ASSUMPTION]` or `[NOTE FOR PM]` tags anywhere. For a solo PRD where the author and the reader are the same person, that is acceptable and I am not scoring it as missing ceremony.

The real gap is silent scope, not silent omission. The memlog records "(decision) La bitacora sale y entra por SAF, porque el release no es depurable y perder la llave obliga a desinstalar". That surface is shipped — `EARNINGS_GATE_BACK_UP` and `EARNINGS_GATE_RESTORE` sit in `EarningsGateScreen.kt`, in the same screen §4.7 describes — and the PRD never mentions it: not in §2, not in §4, not in §13's wiring tables. §4.7's own claim that the tab's contents are "una lista ordenada por fecha" plus a filter is now an incomplete description of the screen it is specifying.

The memlog also says the Update marked the two surfaces "como pendientes al cierre", while §13's wiring table presents them as done. The table is the accurate one — the repo has the code and the tests — so this is a stale memlog note rather than a PRD defect, but it means the memlog and the PRD disagree about state.

### Findings
- **high** Backup/restore of the log is shipped and undocumented (§2, §4, §13) — a SAF export/import decision in the memlog, wired in `EarningsGateScreen.kt`, appears nowhere in the PRD; §4.7 describes the same screen without it. *Fix:* add a §4.8 (or a §4.7 sub-block) for backup/restore with the reason from the memlog — a release build is not debuggable, and losing the key forces an uninstall — plus a row in a §13 wiring table.
- **low** Memlog and §13 disagree on §4.7's state (memlog line 17 vs §13) — the memlog says the two surfaces were left pending at close; the code and tests exist. *Fix:* none needed in the PRD; correct on the next memlog write.

## Downstream usability — thin

This PRD is close to standalone — it feeds Juan and a build loop, not a UX-then-architecture-then-stories chain — so the rubric says this dimension matters less, and I am not treating its weaknesses as blockers. Recording them anyway, since §13 is already being used as a source-extraction table.

There is no glossary, and the vocabulary has drifted with use. The event log is "log de eventos" in §2, §7 and §10, "bitácora" throughout §11, §13 and §4.7, and "registro por reporte" in §4.7's opening. `implied move` and "movimiento implícito" alternate. "Movimiento priceado", "movimiento del evento" and "movimiento implícito" all appear in §13 and are not the same quantity — §13's "El horizonte del ratio" distinguishes total from event, but §4.1 and §4.3 still say `implied_move` for what is now the total, and the ratio in §4.3 divides by it. §4.3's formula, read literally, is the pre-fix behaviour the document later says was wrong.

There are no FR/UJ/SM IDs; sections are referenced by § number and every § reference I checked resolves (§4.7 → §13 `EarningsCaptureWorker`; §13 → §4.6; §12 → §13). The §13 wiring tables are genuinely extractable — file, behaviour, and PRD section per row.

### Findings
- **high** §4.3's ratio formula contradicts the horizon fix (§4.3 vs §13 "El horizonte del ratio") — §4.3 divides `implied_move` by the historical median, and §13 says that mixing two windows was the bug and that the ratio now uses the event component. *Fix:* rewrite §4.3's formula with the event move as numerator and cross-reference §13.
- **medium** Glossary drift on the core noun (throughout) — log de eventos / bitácora / registro, implied move / movimiento implícito / movimiento priceado / movimiento del evento. *Fix:* a six-line glossary near §2 fixing one term each; §4.7 is the section that most needs it, since it names "bps", "bitácora" and "captura" in three different registers.

## Shape fit — strong

The shape matches the product. No UJs, no personas, no market sizing — right for a single-operator tool, and the rubric explicitly permits a capability-spec shape here. The success criteria in §9 are operational rather than user-facing, which is the correct call for one reader. Rigor is light and the substance bar still holds.

The brownfield dimension is where shape fit is most load-bearing, and the code references are accurate where I spot-checked them: `EarningsGateUi.matching` filters both lists by prefix and leaves `damagedLines`/`lastCapture` untouched; `eventsFor` returns the first upcoming and the first settled match; `CAPTURE_WINDOW_DAYS` does live in `:core` (`PreReportBuilder.kt`) and is read by both the recorder and `earningsGateAbsence`; `DetailScreen` renders the `EARNINGS` section from the same `EarningsEventCard`. §4.7's wiring table is the most trustworthy part of the document.

The one shape strain is that §13 has grown into a second document. It now carries specification (the horizon formula, the three chain responses, the cost-cap behaviour) that §4 does not have, under a heading that says "Lo que ya está construido". A reader looking for the spec has to read the implementation log to find it.

### Findings
- **medium** §13 carries specification that §4 lacks (§13 vs §4) — the horizon subtraction, the 50% quote-width `Undecided` rule and the cost-cap outcomes are decisions, filed under a "what is built" heading. *Fix:* promote the rules into §4 (§4.3 and §4.6) and leave §13 with the file-to-section mapping it does best.

## Mechanical notes

- No glossary; drift as noted above (bitácora/log, implied move/movimiento priceado/movimiento del evento).
- No FR/UJ/SM IDs — section numbers act as IDs. Every § cross-reference checked resolves.
- §12 numbering starts at 0.
- No `[ASSUMPTION]` tags and therefore no Assumptions Index. Acceptable at this stake level; nothing to roundtrip.
- No UJs and no protagonists — correct for a single-operator tool, per rubric §7.
- §10 is date-stamped 2026-08-27 but the document header says it was updated 2026-08-28; the stamp is the only signal that §10 is stale.
- §13's test counts (240 in `:core`, plus 37/11/34/14 in `:app`) predate the §4.7 work — `EarningsGatePresentationTest` and `DetailEarningsSectionTest` add cases the counts do not include.
