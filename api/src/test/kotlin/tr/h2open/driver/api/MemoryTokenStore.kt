package tr.h2open.driver.api

class MemoryTokenStore(var tokens: Tokens? = null) : TokenStore {
    var cleared = false
    override fun load() = tokens
    override fun save(tokens: Tokens) {
        this.tokens = tokens
    }
    override fun clear() {
        tokens = null
        cleared = true
    }
}
