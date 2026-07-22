# Discovery universe seeds

These files are the **offline fallback** for **Discovery → Recreate list**. They are **not** startup profiles.

## Live source (preferred)

On **Recreate**, the app first tries the official NASDAQ Trader Symbol Directory over HTTPS:

- `https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt`
- `https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt`

Rules: drop ETF + Test Issue rows, drop preferred `$` series, map class shares `BRK.B` → Yahoo `BRK-B`. If the network call fails, the bundled asset is used.

Regenerate the bundled fallback from the same source:

```powershell
pwsh ./scripts/refresh-us-total-universe.ps1
```

| File | Contents |
| --- | --- |
| `us_total.txt` | One Yahoo-compatible ticker per line (~7k non-ETF equities from NASDAQ + other listed). |

Refresh scores never reads these files; it only scores symbols already stored in `discovery_symbol`.
