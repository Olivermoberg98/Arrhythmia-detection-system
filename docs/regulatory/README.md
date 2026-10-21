# Regulatory Documentation

This folder applies the structure of medical-device regulation to an **educational** ECG arrhythmia monitor. The system is **not a certified medical device**, has not been assessed by a Notified Body, and must not be used for clinical purposes. The standards are used as a design discipline: the documents show the method, and they record honestly where the project does and does not meet it.

## Documents

| ID | Document | Standard | What it answers |
|----|----------|----------|-----------------|
| [SRS-001](SRS-001_SoftwareRequirementsSpec.md) | Software Requirements Specification | IEC 62304 §5.2 | What is the system for, and what must it do? |
| [SAD-001](SAD-001_SoftwareArchitecture.md) | Software Architecture | IEC 62304 §5.3 | How is it built? |
| [SDP-001](SDP-001_SoftwareDevelopmentPlan.md) | Software Development Plan | IEC 62304 §5.1 | How is it developed, versioned and released? Which third-party software does it rely on? |
| [RMF-001](RMF-001_RiskManagementFile.md) | Risk Management File | ISO 14971 | What can go wrong, how is it controlled, and is the residual risk acceptable? |
| [SVVP-001](SVVP-001_VerificationPlan.md) | Verification & Validation Plan | IEC 62304 §5.6–5.7 | How is each requirement tested, and what has actually passed? |
| [TM-001](TM-001_TraceabilityMatrix.csv) | Traceability Matrix | IEC 62304 §5.1.1 | Requirement ↔ risk ↔ code file ↔ test ↔ status, in one table |
| [DPIA-001](DPIA-001_DataProtectionImpact.md) | Data Protection Impact Assessment | GDPR Art. 35 | What personal health data is processed, and with what privacy risks? |
| [MLD-001](MLD-001_MachineLearningDevelopment.md) | Machine Learning Development and Data Management | EU AI Act Art. 9–15, AAMI CR34971 | Where does the training data come from, how good is the model, for whom, and how is it changed? |

```
SDP-001 (process) ── SRS-001 (requirements) ── SAD-001 (design)
                           │       ▲
                           ▼       │ mitigations become requirements
                      RMF-001 (risk) ◄── MLD-001 (ML-specific risks, data, performance)
                           │
                     SVVP-001 (tests + results) ── TM-001 (traceability)
DPIA-001 (privacy) runs alongside, sharing RISK-005
```

## Coverage of an MDR Technical File

How the set compares with what a manufacturer would need to place an AI-enabled ECG device on the EU market under MDR 2017/745 and the AI Act. A device like this would be at least **Class IIa** (Annex VIII Rule 11), so it would need a Notified Body. Its AI would then be **high-risk** under the AI Act.

**Legend:** ✅ present · ⚠️ partial · 🔜 missing, feasible within this project · ⛔ out of scope (see the reason)

| MDR / AI Act element | Status | Where, or why not |
|----------------------|--------|-------------------|
| Intended purpose and classification rationale (Art. 2(12), Annex VIII) | ⚠️ | Intended purpose in SRS-001 §1. Classification is stated here only (Rule 11, Class IIa or higher); no written rationale |
| Software lifecycle (GSPR 17.2, IEC 62304) | ✅ | SDP-001, SRS-001, SAD-001, SVVP-001, TM-001 |
| Risk management (GSPR 1–9, ISO 14971) | ✅ | RMF-001, with ML risks in MLD-001 §6 |
| Verification and validation (Annex II §6) | ⚠️ | SVVP-001 §7: firmware, app and model tested; several on-device tests not yet run; no clinical validation |
| AI data governance, performance, change control (AI Act Art. 9–15) | ⚠️ | MLD-001; gaps listed in its §13 |
| Data protection (GDPR, MDR Art. 110) | ⚠️ | DPIA-001; DPA with Google, Firestore rules review and EU region not done |
| GSPR checklist (Annex I) | 🔜 | A table of every applicable general safety and performance requirement and the evidence for it. Feasible, and the natural index of a real file |
| Cybersecurity (GSPR 17.4, MDCG 2019-16, IEC 81001-5-1) | 🔜 | Missing: no threat model; BLE is unauthenticated (MLD-001 §9). A threat model for BLE, the app and Firebase is feasible |
| Usability engineering (IEC 62366-1) | 🔜 | Missing. A small formative evaluation with a few users, on comprehension of the health status and the disclaimer, is feasible (RMF-001 RISK-007) |
| Instructions for use and labelling (GSPR 23) | ⚠️ | Root README and in-app notices only. An IFU stating performance and limitations is feasible |
| Quality management system (MDR Art. 10(9), ISO 13485, AI Act Art. 17) | ⛔ | See below |
| Clinical evaluation (Art. 61, Annex XIV) | ⛔ | Needs clinical data and equivalence or investigation; MIT-BIH results are technical performance, not clinical evidence |
| Clinical investigation (Art. 62 ff.) | ⛔ | Needs ethics approval, competent-authority notification and safe hardware. Not appropriate with unisolated hardware |
| Electrical safety and EMC (IEC 60601-1, -1-2, -2-47) | ⛔ | Needs an accredited lab and a hardware redesign with galvanic isolation (RMF-001 RISK-004) |
| Post-market surveillance plan, PSUR, vigilance (Art. 83–92) | ⛔ | There is no market. Monitoring data is already collected (MLD-001 §10) |
| Declaration of Conformity, CE marking, UDI, EUDAMED registration | ⛔ | Only relevant when placing a device on the market |
| Notified Body conformity assessment (Annex IX) | ⛔ | As above |

### Is an ISO 13485 quality management system reasonable here?

**Not as a QMS, no.** ISO 13485 describes an organisation, not a set of documents. It requires:
- management responsibility and management review;
- trained, competent staff with training records;
- supplier control;
- internal audits;
- corrective and preventive action (CAPA);
- complaint handling;
- a design history file maintained under document control;
- and finally certification by an audit.

For one developer with no product, writing that would be paperwork that describes nothing real, and an auditor would see that immediately.

What the project already practises are the ISO 13485 *principles* that make sense at this size:

| ISO 13485 clause | Practised as |
|------------------|--------------|
| 4.2.4–4.2.5 Document and record control | Versioned documents with history tables; git; this folder |
| 7.3 Design and development (inputs, outputs, review, verification, transfer, changes) | SRS → SAD → SVVP → TM; changes via pull request; MLD-001 §11 for the model |
| 7.3.9 Design changes | Pull requests with CI; document versions updated with code |
| 7.4 Purchasing / supplier control | SOUP list with pinned versions (SDP-001 §3) |
| 4.1.6 Validation of software tools | **Not done** (MLD-001 §12) |
| 8.5.2 Corrective action | Open actions OA-1..7 in RMF-001 §6, from document review through fix to verification |

That is the honest position, and it is what an engineer joining a regulated company actually needs to understand: the QMS is the company's system, and engineers work inside it through design controls, risk files and change control.

## Recommended Next Steps

In order of value for effort:

1. **Run the pending on-device tests** (TC-CON-08, TC-SYNC-05, TC-REC-03..06, TC-ARR-12, TC-PRIV-01) and close OA-1..5.
2. **Define model acceptance criteria** (MLD-001 §5.6) so that a retrained model can pass or fail.
3. **Threat model and cybersecurity file**, starting with BLE pairing and encryption.
4. **GSPR checklist** linking each Annex I requirement to its evidence in this folder.
5. **Formative usability test** of the health-status wording and the disclaimer.
6. **Record the git commit and data hash with each training run** (MLD-001 §8).
