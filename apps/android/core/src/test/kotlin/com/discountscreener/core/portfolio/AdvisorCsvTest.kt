package com.discountscreener.core.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals

class AdvisorCsvTest {

    @Test
    fun jpm_positions_csv_is_a_holdings_snapshot() {
        assertEquals(CsvKind.HoldingsSnapshot, parseAdvisorCsv(jpmSample())!!.kind)
    }

    @Test
    fun jpm_thousands_commas_stay_thousands() {
        var phyl = parseAdvisorCsv(jpmSample())!!.txs.single { it.symbol == "PHYL" }
        assertEquals(1273.0, phyl.quantityShares)
    }

    @Test
    fun jpm_cost_basis_is_unit_cost() {
        var phyl = parseAdvisorCsv(jpmSample())!!.txs.single { it.symbol == "PHYL" }
        assertEquals(35.28, phyl.price)
    }

    @Test
    fun jpm_cash_rows_do_not_become_positions() {
        assertEquals(listOf("PHYL", "AMZN"), parseAdvisorCsv(jpmSample())!!.txs.map { it.symbol })
    }

    @Test
    fun parser_separates_expected_cash_exclusions_from_parse_failures() {
        var malformed = PHYL.replace("\"1,273\"", "\"not-a-number\"")
        var parsed = parseAdvisorCsv(listOf(JPM_HEADER, CASH, malformed).joinToString("\n"))!!

        assertEquals(2, parsed.ignored)
        assertEquals(1, parsed.expectedExclusions)
        assertEquals(1, parsed.parseFailures)
    }

    @Test
    fun jpm_snapshot_aggregates_to_open_lots() {
        var phyl = aggregateToPositions(parseAdvisorCsv(jpmSample())!!.txs).single { it.symbol == "PHYL" }
        assertEquals(3528L, phyl.avgCostCents)
    }

    @Test
    fun phyl_shares_persist_as_ten_thousandths() {
        var phyl = aggregateToPositions(parseAdvisorCsv(jpmSample())!!.txs).single { it.symbol == "PHYL" }
        assertEquals(12_730_000L, phyl.quantityTenThousandths)
    }

    @Test
    fun jpm_fractional_quantity_rounds_half_up_to_four_decimals() {
        var amzn = aggregateToPositions(parseAdvisorCsv(jpmSample())!!.txs).single { it.symbol == "AMZN" }
        assertEquals(362_954L, amzn.quantityTenThousandths)
    }

    @Test
    fun a_positive_snapshot_lot_below_one_dollar_stays_in_the_book() {
        var lot = aggregateToPositions(
            listOf(CsvTx("PENNY", Side.Buy, quantityShares = 0.5, price = 0.5, date = "")),
        ).single()

        assertEquals(5_000L, lot.quantityTenThousandths)
        assertEquals(50L, lot.avgCostCents)
    }

    @Test
    fun a_subcent_cost_does_not_emit_a_zero_cent_lot() {
        var positions = aggregateToPositions(
            listOf(
                CsvTx("PENNY", Side.Buy, quantityShares = 0.5, price = 0.001, date = ""),
                CsvTx("AMZN", Side.Buy, quantityShares = 1.0, price = 2.0, date = ""),
            ),
        )

        assertEquals(listOf("AMZN"), positions.map { it.symbol })
    }

    @Test
    fun an_unchanged_trade_window_keeps_a_positive_lot_below_one_dollar() {
        var merged = mergeTradesOntoLots(
            listOf(lot("PENNY", qty = 5_000, cost = 50)),
            listOf(CsvTx("PENNY", Side.Buy, quantityShares = 0.1, price = 0.5, date = "2026-08-31")),
            "2026-08-31",
        )

        assertEquals(5_000L, merged.positions.single().quantityTenThousandths)
        assertEquals(50L, merged.positions.single().avgCostCents)
    }

    @Test
    fun jpm_as_of_becomes_the_book_as_of() {
        assertEquals("2026-08-31", parseAdvisorCsv(jpmSample())!!.asOf)
    }

    @Test
    fun jpm_holdings_import_plans_a_confirm_replace() {
        var plan = planAdvisorCsv(parseAdvisorCsv(jpmSample())!!, BookContext(emptyList(), null))
        assertEquals(true, plan is ImportPlan.ConfirmHoldingsReplace)
    }

    @Test
    fun a_holdings_snapshot_removes_lots_the_file_omits() {
        var plan = planAdvisorCsv(
            parseAdvisorCsv(jpmSample())!!,
            BookContext(
                lots = listOf(lot("MSFT", 10_000), lot("AXON", 40_000)),
                bookAsOf = null,
            ),
        ) as ImportPlan.ConfirmHoldingsReplace
        assertEquals(listOf("AXON", "MSFT"), plan.remove)
    }

    @Test
    fun a_cash_only_holdings_snapshot_refuses() {
        var plan = planAdvisorCsv(
            parseAdvisorCsv(listOf(JPM_HEADER, CASH, SWEEP).joinToString("\n"))!!,
            BookContext(lots = listOf(AMZN_LOT), bookAsOf = null),
        )
        assertEquals(RefuseReason.EmptyKeep, (plan as ImportPlan.Refuse).reason)
    }

    @Test
    fun chase_transactions_csv_is_a_trades_window() {
        assertEquals(CsvKind.TradesWindow, parseAdvisorCsv(chaseSample())!!.kind)
    }

    @Test
    fun chase_reads_price_usd() {
        var axon = parseAdvisorCsv(chaseSample())!!.txs.single { it.symbol == "AXON" }
        assertEquals(563.35, axon.price)
    }

    @Test
    fun chase_sell_quantity_is_absolute() {
        var axon = parseAdvisorCsv(chaseSample())!!.txs.single { it.symbol == "AXON" }
        assertEquals(4.0, axon.quantityShares)
    }

    @Test
    fun chase_reinvest_is_a_buy() {
        var phyl = parseAdvisorCsv(chaseSample())!!.txs.single { it.symbol == "PHYL" }
        assertEquals(Side.Buy, phyl.side)
    }

    @Test
    fun a_trades_window_without_lots_refuses() {
        var plan = planAdvisorCsv(
            parseAdvisorCsv(chaseSample())!!,
            BookContext(emptyList(), "2026-08-31"),
        )
        assertEquals(RefuseReason.TradesWithoutBook, (plan as ImportPlan.Refuse).reason)
    }

    @Test
    fun a_blank_file_refuses_as_unreadable() {
        var plan = planParsedCsv("x\n", BookContext(emptyList(), null))
        assertEquals(RefuseReason.Unreadable, (plan as ImportPlan.Refuse).reason)
    }

    @Test
    fun a_holdings_snapshot_without_as_of_refuses() {
        var plan = planParsedCsv(
            "Asset Class,Ticker,Quantity,Unit Cost,As of\nEquity,AMZN,10,200.00,\n",
            BookContext(emptyList(), null),
        )
        assertEquals(RefuseReason.AsOfUnparseable, (plan as ImportPlan.Refuse).reason)
    }

    @Test
    fun an_empty_book_as_of_string_refuses() {
        var plan = planAdvisorCsv(
            parseAdvisorCsv(chaseSample())!!,
            BookContext(listOf(AMZN_LOT), ""),
        )
        assertEquals(RefuseReason.MissingBookAsOf, (plan as ImportPlan.Refuse).reason)
    }

    @Test
    fun an_empty_opened_at_takes_the_earliest_blotter_buy() {
        var merged = mergeTradesOntoLots(
            listOf(lot("AMZN", 100_000, 20_000, null)),
            listOf(CsvTx("AMZN", Side.Buy, 10.0, 200.0, "2026-06-02")),
            "2026-08-31",
        )
        assertEquals("2026-06-02", merged.positions.single().openedAt)
    }

    @Test
    fun a_trades_window_without_book_as_of_refuses() {
        var plan = planAdvisorCsv(
            parseAdvisorCsv(chaseSample())!!,
            BookContext(listOf(AMZN_LOT), null),
        )
        assertEquals(RefuseReason.MissingBookAsOf, (plan as ImportPlan.Refuse).reason)
    }

    @Test
    fun trades_on_as_of_do_not_change_quantity_or_cost() {
        var merged = mergeTradesOntoLots(
            listOf(AMZN_LOT),
            listOf(CsvTx("AMZN", Side.Buy, 10.0, 50.0, "2026-08-31")),
            "2026-08-31",
        )
        var amzn = merged.positions.single { it.symbol == "AMZN" }
        assertEquals(AMZN_LOT, amzn.copy(openedAt = AMZN_LOT.openedAt))
    }

    @Test
    fun trades_before_as_of_do_not_change_quantity_or_cost() {
        var merged = mergeTradesOntoLots(
            listOf(AMZN_LOT),
            listOf(CsvTx("AMZN", Side.Sell, 4.0, 200.0, "2026-08-28")),
            "2026-08-31",
        )
        var amzn = merged.positions.single()
        assertEquals(100_000L to 20_000L, amzn.quantityTenThousandths to amzn.avgCostCents)
    }

    @Test
    fun a_buy_after_as_of_adds_size_and_blends_cost() {
        var merged = mergeTradesOntoLots(
            listOf(AMZN_LOT),
            listOf(CsvTx("AMZN", Side.Buy, 10.0, 220.0, "2026-09-01")),
            "2026-08-31",
        )
        var amzn = merged.positions.single()
        assertEquals(200_000L to 21_000L, amzn.quantityTenThousandths to amzn.avgCostCents)
    }

    @Test
    fun confirm_merge_advances_android_as_of() {
        var plan = planAdvisorCsv(
            ParsedCsv(
                txs = listOf(CsvTx("AMZN", Side.Buy, 10.0, 220.0, "2026-09-01")),
                ignored = 0,
                expectedExclusions = 0,
                parseFailures = 0,
                format = "Chase",
                kind = CsvKind.TradesWindow,
                asOf = null,
            ),
            BookContext(listOf(AMZN_LOT), "2026-08-31"),
        ) as ImportPlan.ConfirmTradesMerge
        assertEquals("2026-09-01", plan.nextBookAsOf)
    }

    @Test
    fun a_second_plan_after_as_of_advance_keeps_qty() {
        var after = mergeTradesOntoLots(
            listOf(AMZN_LOT),
            listOf(CsvTx("AMZN", Side.Buy, 10.0, 220.0, "2026-09-01")),
            "2026-08-31",
        )
        var again = mergeTradesOntoLots(
            after.positions,
            listOf(CsvTx("AMZN", Side.Buy, 10.0, 220.0, "2026-09-01")),
            "2026-09-01",
        )
        assertEquals(200_000L, again.positions.single().quantityTenThousandths)
    }

    @Test
    fun coinbase_headers_refuse_ledger_apply() {
        var text = "Timestamp,Transaction Type,Asset,Quantity Transacted,Spot Price Currency,Spot Price at Transaction,Subtotal,Total (inclusive of fees and/or spread),Fees and/or Spread,Notes\n2026-01-01,Buy,BTC,1,USD,100,100,100,0,x\n"
        var plan = planParsedCsv(text, BookContext(emptyList(), null))
        assertEquals(RefuseReason.LedgerApplyUnsupported, (plan as ImportPlan.Refuse).reason)
    }

    @Test
    fun schwab_headers_refuse_ledger_apply() {
        var text = "Date,Action,Symbol,Description,Quantity,Price,Fees & Comm,Amount\n01/02/2026,Buy,AMZN,Amazon,1,100,0,-100\n"
        var plan = planParsedCsv(text, BookContext(emptyList(), null))
        assertEquals(RefuseReason.LedgerApplyUnsupported, (plan as ImportPlan.Refuse).reason)
    }

    @Test
    fun generic_headers_refuse_ledger_apply() {
        var text = "fecha;ticker;cantidad;precio\n01/02/2026;AMZN;1;100\n"
        var plan = planParsedCsv(text, BookContext(emptyList(), null))
        assertEquals(RefuseReason.LedgerApplyUnsupported, (plan as ImportPlan.Refuse).reason)
    }

    @Test
    fun two_snapshot_rows_for_one_ticker_become_one_lot() {
        var txs = parseAdvisorCsv(listOf(JPM_HEADER, AMZN, AMZN).joinToString("\n"))!!.txs
        assertEquals(1, aggregateToPositions(txs).size)
    }

    @Test
    fun a_sell_larger_than_the_lot_is_skipped() {
        var merged = mergeTradesOntoLots(
            listOf(AMZN_LOT),
            listOf(CsvTx("AMZN", Side.Sell, 11.0, 220.0, "2026-09-01")),
            "2026-08-31",
        )
        assertEquals(1, merged.skipped)
    }

    @Test
    fun a_trades_window_that_closes_a_lot_lists_it_for_removal() {
        var plan = planAdvisorCsv(
            parseAdvisorCsv(chaseSample())!!,
            BookContext(
                lots = listOf(lot("AXON", 40_000, 43_600), AMZN_LOT),
                bookAsOf = "2026-08-15",
            ),
        ) as ImportPlan.ConfirmTradesMerge
        assertEquals(listOf("AXON"), plan.remove)
    }

    @Test
    fun policy_version_is_three() {
        assertEquals("advisor-csv-import/3", ADVISOR_CSV_POLICY_VERSION)
    }
}

class HeldPinTest {

    @Test
    fun exact_ticker_is_held() {
        assertEquals(true, isHeld("AMZN", setOf("AMZN")))
    }

    @Test
    fun mixed_case_lot_holds_uppercase_row() {
        assertEquals(true, isHeld("AMZN", heldTickers(listOf(lot("amzn", 1)))))
    }

    @Test
    fun prefix_is_not_held() {
        assertEquals(false, isHeld("AMZN", setOf("A")))
    }

    @Test
    fun held_rows_sit_first() {
        var rows = listOf("MSFT", "AMZN")
        assertEquals(listOf("AMZN", "MSFT"), pinHeldFirst(rows, setOf("AMZN")) { it })
    }

    @Test
    fun absent_lot_invents_no_row() {
        var rows = listOf("MSFT")
        assertEquals(listOf("MSFT"), pinHeldFirst(rows, setOf("PHYL")) { it })
    }
}

private val AMZN_LOT = lot("AMZN", 100_000, 20_000, "2024-01-15")

private fun lot(
    symbol: String,
    qty: Long,
    cost: Long = 10_000,
    opened: String? = null,
) = PortfolioLot(symbol, qty, cost, opened)

private const val JPM_HEADER =
    "Asset Class,Asset Strategy,Asset Strategy Detail,Description,Ticker,CUSIP,Quantity,Base CCY,Local CCY,Price,PriceInd,Local Price,Today's Price Change,Price Change %,Pricing Date,Value,Today's Value Change,Value Change %,Local Value,Cost,Unit Cost,Local Unit Cost,Orig Cost (Base),Orig Cost (Local),Cost Source,Local Cost,Unrealized G/L Amt.,Orig. \$ Gain/Loss (Base),Orig. \$ Gain/Loss (Local),Local Unrealized G/L Amt.,Unrealized Gain/Loss (%),Orig. % Gain/Loss (Base),Orig. % Gain/Loss (Local),Local Unrealized Gain/Loss (%),Disallowed Loss (Base),Disallowed Loss (Local),Acquisition Date,Adj Date,Accrued/Income Earned,Local Accrued/Income Earned,Accrued Income,Local Accrued Income,Est. Annual Income,Local Est. Annual Income,YTM,Maturity Date,Coupon Rate,S&P Rating,Moody Rating,Buy/Call Amount,Buy/Call Currency,Sell/Put Amount,Sell/Put Currency,Market Spot Rate,Market Forward Rate,Contract Rate,Subscription Amount,Net Distributions,Used/Outstanding,Interest Rate,Finance Charges (MTD),As of,Acct Type,Accounting Method,Current Face Value,Disclaimers-Cost,Disclaimers-Quantity,Dividend Yield,Amount invested,7-day average yield,ISIN"

private const val PHYL =
    """"Fixed Income","Non-Investment Grade Corporate","","PGIM ETF TRUST PGIM ACTIVE HIGH YIELD BOND ETF","PHYL","69344A206","1,273","USD","","34.61","false","","-0.17","-0.49","08/30/2026 08:00:00","44,058.53","-216.41","-0.49","","44,906.43","35.28","","44,906.43","","","","-847.9","-847.9","","","-1.89","-1.89","","","","","","","3,156.7","","0","","3,080.66","","0","","0","","","","","","","1","0","0","1,273","0","44,058.53","0","","08/31/2026","Cash","Original Cost","","","","0","","","US69344A2069""""

private const val CASH =
    """"Cash & Money Market Funds","Cash","","US DOLLAR","","0USDPRAA7","16,339.32","USD","","1","false","","0","0","","16,339.32","0","0","","16,339.32","1","","0","","","","0","0","","","0","0","","","","","","","","","0","","","","0","","0","","","","","","","1","0","0","16,339.32","0","16,339.32","0","","08/31/2026","Cash","Original Cost","","","","0","","",""""

private const val AMZN =
    """"Equity","US Large Cap","","AMAZON.COM INC","AMZN","023135106","36.29536","USD","","259.45","false","","-6.98","-2.62","08/30/2026 08:00:00","9,416.83","-253.34","-2.62","","7,768.21","214.03","","7,768.21","","","","1,648.62","1,648.62","","","21.22","21.22","","","","","","","","","0","","","","0","","0","","","","","","","1","0","0","36.29536","0","9,416.83","0","","08/31/2026","Cash","Original Cost","","","","0","","","US0231351067""""

private const val SWEEP =
    """"Cash & Money Market Funds","Money Market Funds","","CHASE DEPOSIT SWEEP JPMORGAN CHASE BANK NA","QACDS","","1,879.55","USD","","1","false","","0","0","08/28/2026 08:00:00","1,879.55","0","0","","1,879.55","1","","1,879.55","","","","0","0","","","0","0","","","","","","","","","0","","","","0","","0","","","","","","","1","0","0","1,879.55","0","1,879.55","0","","08/31/2026","Cash","Original Cost","","","","0","","",""""

private fun jpmSample() = listOf(JPM_HEADER, PHYL, CASH, AMZN, SWEEP, "FOOTNOTES").joinToString("\n")

private const val CHASE_HEADER =
    "Trade Date,Post Date,Settlement Date,Account Name,Account Number,Account Type,Type,Description,Cusip,Ticker,Security Type,Local Currency,Price USD,Price Local,Quantity,G/L Short USD,G/L Short Local,G/L Long USDs,G/L Long Local,Amount USD,Amount Local,Income USD,Income Local,Balance,Commissions USD,Commissions Local,Tran Code,Tran Code Description,Broker,Check Number,Tax Withheld"

private fun chaseRow(date: String, type: String, ticker: String, price: String, qty: String): String {
    var cols = Array(31) { "" }
    cols[0] = date
    cols[6] = type
    cols[9] = ticker
    cols[12] = price
    cols[14] = qty
    return cols.joinToString(",") { "\"$it\"" }
}

private fun chaseSample() = listOf(
    CHASE_HEADER,
    chaseRow("8/31/2026", "Sell", "AXON", "563.35", "-4"),
    chaseRow("8/15/2026", "Reinvest", "PHYL", "35.00", "5"),
    chaseRow("8/10/2026", "Dividend", "AMZN", "0", "0"),
    chaseRow("9/1/2026", "Buy", "AMZN", "220", "10"),
).joinToString("\n")
