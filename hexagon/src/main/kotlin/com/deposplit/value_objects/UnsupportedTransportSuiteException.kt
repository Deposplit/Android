package com.deposplit.value_objects

/**
 * Thrown by [com.deposplit.driving_adapters.ShareEncryption.decrypt] when a ciphertext's leading
 * suite-tag byte doesn't match any [TransportSuite] this app version supports — never a silent
 * misparse.
 */
class UnsupportedTransportSuiteException(message: String) : Exception(message)
