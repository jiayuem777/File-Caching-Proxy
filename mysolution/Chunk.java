/**
 * Chunk.java
 * @author Jiayue Mao
 * Andrew ID: jiayuem
 */

import java.io.Serializable;

public class Chunk implements Serializable{

    private static final long serialVersionUID = 2L;
    public int size; // chunk size
    public boolean remain; // determine whether read/write process needs following chunks
    byte[] content; // chunk content buffer

    /**
     * Chunk constructor
     * @param size      the size of the chunk
     * (If size < 0, means there's an error)
     */
    public Chunk(int size) {
        this.size = size;
        if (size > 0)
            content = new byte[size];
    }
}