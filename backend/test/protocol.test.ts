import { describe, expect, it } from "vitest";

import {
  trackSchema,
  websocketEnvelopeSchema,
} from "../src/protocol/models.js";

describe("protocol models", () => {
  it("accepts a complete ready track", () => {
    const track = trackSchema.parse({
      trackId: "track-1",
      roomId: "room-1",
      title: "Test Song",
      artist: null,
      durationMs: 123_000,
      fileName: "test.flac",
      fileSize: 456,
      fileHash: "a".repeat(64),
      uploaderId: "user-1",
      uploaderName: "Alice",
      orderIndex: 0,
      status: "READY",
      createdAt: 1_710_000_000_000,
    });

    expect(track.status).toBe("READY");
  });

  it("rejects an invalid track status", () => {
    expect(() =>
      trackSchema.parse({
        trackId: "track-1",
        roomId: "room-1",
        title: "Test Song",
        artist: null,
        durationMs: 123_000,
        fileName: "test.flac",
        fileSize: 456,
        fileHash: "a".repeat(64),
        uploaderId: "user-1",
        uploaderName: "Alice",
        orderIndex: 0,
        status: "UNKNOWN",
        createdAt: 1_710_000_000_000,
      }),
    ).toThrow();
  });

  it("requires the common websocket envelope fields", () => {
    const event = websocketEnvelopeSchema.parse({
      type: "TRACK_READY",
      payload: { trackId: "track-1" },
      serverTimeMs: 1_710_000_000_000,
    });

    expect(event.type).toBe("TRACK_READY");
    expect(() =>
      websocketEnvelopeSchema.parse({
        type: "TRACK_READY",
        payload: {},
      }),
    ).toThrow();
  });
});

