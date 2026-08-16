package com.discountscreener.android.data.remote

import com.discountscreener.core.engine.IssuerComponentSet

fun interface IssuerComponentLookup {
    suspend fun lookup(symbol: String, companyName: String?): IssuerComponentSet?
}
