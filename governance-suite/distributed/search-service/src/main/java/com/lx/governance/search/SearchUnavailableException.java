package com.lx.governance.search;

public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException(Throwable cause) { super("商品搜索暂不可用，请稍后重试", cause); }
}
