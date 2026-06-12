export type TrackRow = {
  track_id: string;
  room_id: string;
  title: string;
  artist: string | null;
  duration_ms: number;
  file_name: string;
  file_size: number;
  file_hash: string;
  storage_path: string | null;
  uploader_id: string;
  uploader_name: string;
  order_index: number;
  status: "UPLOADING" | "READY" | "FAILED";
  created_at: number;
};

export function toTrack(row: TrackRow) {
  return {
    trackId: row.track_id,
    roomId: row.room_id,
    title: row.title,
    artist: row.artist,
    durationMs: row.duration_ms,
    fileName: row.file_name,
    fileSize: row.file_size,
    fileHash: row.file_hash,
    uploaderId: row.uploader_id,
    uploaderName: row.uploader_name,
    orderIndex: row.order_index,
    status: row.status,
    createdAt: row.created_at,
  };
}

