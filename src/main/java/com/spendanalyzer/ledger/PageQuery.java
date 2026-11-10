package com.spendanalyzer.ledger;

/** Which slice of a list to return. {@code page} starts at 0. */
public record PageQuery(int page, int size) {

	public static final int DEFAULT_SIZE = 20;

	public static final int MAX_SIZE = 100;

	public long offset() {
		return (long) page * size;
	}

}
