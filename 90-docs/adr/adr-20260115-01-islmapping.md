# ADR-20260115-01-islmapping

## Purpose
Define the mapping of business types to ISIC/ISCO codes for the Cambodia market entry analysis.

## Context
Based on the analysis of Cambodian business environments (Phnom Penh, 2026) and the ISIC classification system (JETRO reports, World Bank Enterprise Surveys 2025/2026), the following business types are mapped to their appropriate ISIC/ISCO codes:

| Rank | Business Type | ISIC Code | ISIC Rev | Source |
|------|---------------|-----------|----------|---------|
| 1 | Bakery / Cafe | 1071 | Rev. 5 | JETRO Cambodia FDI report (2026) |
| 2 | Salon / Beauty | 9602 | Rev. 5 | JETRO Cambodia (2026) |
| 3 | School / Education | 4711 | Rev. 5 | JETRO Cambodia (2026) |
| 4 | Retail (general) | 5610 | Rev. 5 | JETRO Cambodia (2026) |
| 5 | Restaurant (full-service) | 5610 | Rev. 5 | JETRO Cambodia (2026) |
| 6 | Fitness / Gym | 9602 | Rev. 5 | JETRO Cambodia (2026) |

## Justification

### 1. Bakery / Cafe (Rank 1)
- **ISIC 1071** – *Bakery Products* – Covers bakery operations, bread, pastries, coffee shops.
- **Why**: Low capital requirement (min. 4M Riel ≈ $1,000), fast permitting (20 days, One-Window), high demand in Phnom Penh.
- **Cambodian context**: 121 Japanese restaurants, 518 beauty salons indicate strong tourism and expatriate demand.

### 2. Salon / Beauty (Rank 2)
- **ISIC 9602** – *Fitness* – Includes hair salons, beauty services, nail bars.
- **Why**: High unit margin ($30–60 per service), quick permit, competitive but saturated market.
- **Cambodian context**: 518 beauty salons reported; high growth potential.

### 3. School / Education (Rank 3)
- **ISIC 4711** – *Schools* – Primary/secondary education institutions.
- **Why**: Government-regulated sector, relatively low capital, steady demand.
- **Cambodian context**: Growing enrollment, especially in urban areas.

### 4. Retail (Rank 4)
- **ISIC 5610** – *Restaurants and Mobile Food* – Covers cafes, street food, takeaway.
- **Why**: Broad category covering 121 Japanese restaurants; moderate capital, logistics challenges (power outages 0.9×/month).
- **Cambodian context**: High competition but clear niche for specialty cafes.

### 5. Restaurant (Full-Service) (Rank 5)
- **ISIC 5610** – *Restaurants and Mobile Food* – Same code as retail but focuses on full-service dining.
- **Why**: Higher capital than casual cafes, higher margins, but intense competition in Phnom Penh.
- **Cambodian context**: 121 Japanese restaurants indicate mature market; opportunity for differentiated offerings.

### 6. Fitness / Gym (Rank 6)
- **ISIC 9602** – *Fitness* – Gyms, wellness centers, personal training.
- **Why**: Growing health-conscious trend, but high equipment/logistics costs.
- **Cambodian context**: Limited direct competition from Japanese entrants; opportunity for premium studios.

## Implementation Status
- **Cloud-itonami App** (main analysis repo): The mapping is documented in this ADR and will be used as the basis for:
  - `cloud-itonami-isic-1071` (Bakery) – add reference to ISIC 1071
  - `cloud-itonami-isic-9602` (Fitness) – add reference to ISIC 9602
  - `cloud-itonami-isic-4711` (School) – add reference to ISIC 4711
  - `cloud-itonami-isic-5610` (Retail/Restaurant) – add reference to ISIC 5610
- **ISIC Actor Repos** (separate): Each ISIC repo already contains actor implementations for these codes. The mapping ensures the main analysis repo correctly references the right actor for each business type.

## Governance
- All proposals to modify ISIC/ISCO mappings must go through the **maturity-fleet PR workflow** (see `maturity-fleet-pr-workflow` skill).
- Changes to this ADR require a PR to `cloud-itonami/app/adr-20260115-01-islmapping.md`.
- Future updates to the mapping should be coordinated with the ISIC actor maintainers in each `cloud-itonami-isic-*-repo`.

## Related
- `cloud-itonami-isic-1071` – Bakery Products actor
- `cloud-itonami-isic-9602` – Fitness actor
- `cloud-itonami-isic-4711` – Schools actor
- `cloud-itonami-isic-5610` – Restaurants & Mobile Food actor
- `cloud-itonami-isic-3111` – Capital-intensive sectors (not used here)

## Approval
- Approved by: [Author]
- Date: 2026-09-25
