package com.ai.walmartads.importing.parser;

import java.util.List;

public record ParsedRows<T>(int sourceRowCount, List<T> rows) {
}
