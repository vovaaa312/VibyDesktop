package com.viby.core.data;

import java.util.List;

/**
 * The Android Room {@code PendingDownloadDao} interface. {@link #of(Database)} backs it with the
 * desktop JDBC DAO; tests ported from Android implement it with in-memory fakes.
 */
public interface RoomPendingDownloadDao {

    long insert(PendingDownload pending);

    void delete(long id);

    List<PendingDownload> getAllSync();

    void updateQueuePosition(long id, long position);

    void updatePaused(long id, boolean paused);

    void updateTrackOrder(long id, String trackOrderJson);

    static RoomPendingDownloadDao of(Database database) {
        PendingDownloadDao pending = new PendingDownloadDao(database);
        return new RoomPendingDownloadDao() {
            @Override
            public long insert(PendingDownload row) {
                return RoomTrackDao.call(() -> pending.insert(row));
            }

            @Override
            public void delete(long id) {
                RoomTrackDao.run(() -> pending.delete(id));
            }

            @Override
            public List<PendingDownload> getAllSync() {
                return RoomTrackDao.call(pending::all);
            }

            @Override
            public void updateQueuePosition(long id, long position) {
                RoomTrackDao.run(() -> pending.setQueuePosition(id, position));
            }

            @Override
            public void updatePaused(long id, boolean paused) {
                RoomTrackDao.run(() -> pending.setPaused(id, paused));
            }

            @Override
            public void updateTrackOrder(long id, String trackOrderJson) {
                RoomTrackDao.run(() -> pending.setTrackOrder(id, trackOrderJson));
            }
        };
    }
}
