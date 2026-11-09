package co.edu.corhuila.dlc.worker.adapter.out.http;

/** The bearer service JWT for one owner audience: a mounted file or one issued by IAM (ADR-020). */
public interface ServiceToken {
    String current();

    /** The owner rejected the token; a renewable source fetches a fresh one on the next call. */
    default void invalidate() { }
}
