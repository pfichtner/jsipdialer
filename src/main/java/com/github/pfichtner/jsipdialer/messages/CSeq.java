package com.github.pfichtner.jsipdialer.messages;

public class CSeq {

	private final int sequence;

	public static final String C_SEQ = "CSeq";

	public static CSeq parse(String string) {
		String[] split = string.split("\\ ");
		return of(Integer.parseInt(split[0]));
	}

	public static CSeq of(int sequence) {
		return new CSeq(sequence);
	}

	private CSeq(int sequence) {
		this.sequence = sequence;
	}

	public CSeq next() {
		return new CSeq(sequence + 1);
	}

	public int sequence() {
		return sequence;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + sequence;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CSeq other = (CSeq) obj;
		if (sequence != other.sequence)
			return false;
		return true;
	}

}
