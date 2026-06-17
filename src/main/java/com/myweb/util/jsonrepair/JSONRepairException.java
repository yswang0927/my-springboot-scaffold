package com.myweb.util.jsonrepair;

public class JSONRepairException extends RuntimeException {
    private final int position;

    public JSONRepairException(String message, int position) {
        super(message + " at position " + position);
        this.position = position;
    }

    public int getPosition() {
        return position;
    }

}
