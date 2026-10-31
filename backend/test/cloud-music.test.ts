import { createCipheriv } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import { assessDuration, CloudMusicClient, CloudMusicError, orderCandidates, type CloudTrack } from "../src/cloud/music.js";

const track: CloudTrack = { source: "qq", id: "songMID", mediaId: "mediaMID", title: "Test", artists: ["Artist"], album: "Album", durationMs: 100000, songType: 0 };
function encrypted(body: object): Response {
  const cipher = createCipheriv("aes-256-cbc", Buffer.from("xSdddRXNm0P5689HI34TqJNzDb666666"), Buffer.from("dgjsJhf58aFlOk15"));
  return new Response(Buffer.concat([cipher.update(JSON.stringify(body)), cipher.final()]).toString("base64"));
}

describe("cloud music", () => {
  it("builds QQ artwork only from a valid album MID", async () => {
    const response = (mid: string) => new Response(JSON.stringify({ code: 0, ["music.search.SearchCgiService"]: { code: 0, data: { body: { song: { list: [{ mid: "song", title: "Test", album: { mid } }] } } } } }));
    const music = new CloudMusicClient(vi.fn().mockResolvedValueOnce(response("Album123")).mockResolvedValueOnce(response("../bad")));
    expect((await music.search("qq", "test"))[0]?.coverUrl).toBe("https://y.gtimg.cn/music/photo_new/T002R300x300M000Album123_1.jpg?max_age=2592000");
    expect((await music.search("qq", "test"))[0]?.coverUrl).toBe("");
  });
  it("requires matching KuGou lyric candidate metadata and returned ID", async () => {
    const selected = { ...track, source: "kugou" as const, id: "a".repeat(32), title: "Test", artists: ["Artist"] };
    const transport = vi.fn().mockResolvedValueOnce(encrypted({ status: 200, candidates: [{ song: "Other", singer: "Artist", id: "1", accesskey: "key" }, { song: "Test", singer: "Artist", id: "2", accesskey: "key" }] }))
      .mockResolvedValueOnce(encrypted({ status: 200, id: "2", decodeContent: "[00:01.00]line" }));
    expect((await new CloudMusicClient(transport).lyrics(selected)).text).toBe("[00:01.00]line");
    expect(JSON.parse(String(transport.mock.calls[1]?.[1]?.body)).apiParam).toBe("/lyric?id=2&accesskey=key&decode=true&fmt=lrc");
    const mismatch = vi.fn().mockResolvedValueOnce(encrypted({ status: 200, candidates: [{ song: "Test", singer: "Other", id: "2", accesskey: "key" }] }));
    await expect(new CloudMusicClient(mismatch).lyrics(selected)).rejects.toThrow("No lyrics match");
    const wrongId = vi.fn().mockResolvedValueOnce(encrypted({ status: 200, candidates: [{ song: "Test", singer: "Artist", id: "2", accesskey: "key" }] }))
      .mockResolvedValueOnce(encrypted({ status: 200, id: "3", decodeContent: "wrong" }));
    await expect(new CloudMusicClient(wrongId).lyrics(selected)).rejects.toThrow("Lyrics request failed");
  });
  it("retrieves lyrics through the confirmed NetEase gateway route", async () => {
    const transport = vi.fn().mockResolvedValue(encrypted({ code: 200, lrc: { lyric: "[00:01.00]line" } }));
    const result = await new CloudMusicClient(transport).lyrics({ ...track, source: "netease", id: "123" });
    expect(result.text).toBe("[00:01.00]line");
    expect(JSON.parse(String(transport.mock.calls[0]?.[1]?.body)).apiParam).toBe("/lyric?id=123");
  });
  it("uses the real gateway cipher and converts NetEase page to offset", async () => {
    const transport = vi.fn<(url: string, init: RequestInit) => Promise<Response>>(async () => encrypted({ code: 200, result: { songs: [{ id: 12, name: "A", ar: [{ name: "B" }], al: { name: "C" }, dt: 1234, l: { size: 100 }, sq: { size: 1000 } }] } }));
    const results = await new CloudMusicClient(transport).search("netease", "hello", 2);
    expect(results[0]).toMatchObject({ id: "12", durationMs: 1234, album: "C", availableQualities: ["standard", "lossless"] });
    expect(JSON.parse(String(transport.mock.calls[0]?.[1]?.body)).apiParam).toContain("offset=30");
  });

  it("uses the standard NetEase endpoint when the multi-quality response is empty", async () => {
    const transport = vi.fn().mockResolvedValueOnce(encrypted({ code: 200, data: [] })).mockResolvedValueOnce(encrypted({ code: 200, data: [{ url: "https://audio.example/a", br: 128, format: "mp3" }] }));
    const result = await new CloudMusicClient(transport).resolve({ ...track, source: "netease" });
    expect(result.method).toBe("/song/url/v1");
    expect(result.candidates[0]?.bitrate).toBe(128);
  });

  it("falls back after a NetEase network error, but does not invent a KuGou fallback", async () => {
    const transport = vi.fn().mockRejectedValueOnce(new Error("network"))
      .mockResolvedValueOnce(encrypted({ code: 200, data: [{ url: "https://audio.example/a", br: 128 }] }));
    const result = await new CloudMusicClient(transport).resolve({ ...track, source: "netease" });
    expect(result.method).toBe("/song/url/v1");
    const kugouTransport = vi.fn().mockRejectedValue(new Error("network"));
    await expect(new CloudMusicClient(kugouTransport).resolve({ ...track, source: "kugou" })).rejects.toBeInstanceOf(CloudMusicError);
    expect(kugouTransport).toHaveBeenCalledTimes(1);
  });

  it("requires the QQ song ID and media filename to match", async () => {
    const transport = vi.fn<(url: string, init: RequestInit) => Promise<Response>>(async () => Response.json({ code: 0, req_0: { code: 0, data: { sip: ["https://audio.example/"], midurlinfo: [
      { songmid: "other", filename: "M500mediaMID.mp3", result: 0, purl: "wrong" },
      { songmid: "songMID", filename: "M500mediaMID.mp3", result: 0, purl: "ok" },
      { songmid: "songMID", filename: "M800mediaMID.mp3", result: 0, purl: "//evil.example/" },
    ] } } }));
    const result = await new CloudMusicClient(transport).resolve(track);
    expect(result.candidates.map(x => x.url)).toEqual(["https://audio.example/ok"]);
    expect(result.resolvedTitle).toBe("");
    const request = JSON.parse(String(transport.mock.calls[0]?.[1]?.body));
    expect(request.req_0.param.songmid[0]).toBe("songMID");
    expect(request.req_0.param.filename[0]).toBe("M500mediaMID.mp3");
  });

  it("does not fall back to a different song when QQ gives no address", async () => {
    const transport = vi.fn(async () => Response.json({ code: 0, req_0: { code: 0, data: { sip: ["https://audio.example/"], midurlinfo: [] } } }));
    await expect(new CloudMusicClient(transport).resolve(track)).rejects.toBeInstanceOf(CloudMusicError);
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it("orders known bitrates without claiming unknown formats are highest quality", () => {
    const rows = [{ url: "https://a", bitrate: 128, format: "mp3" }, { url: "https://b", bitrate: null, format: "flac" }, { url: "https://c", bitrate: 320, format: "mp3" }];
    expect(orderCandidates(rows, "highest").map(x => x.bitrate)).toEqual([320, 128, null]);
    expect(rows[0]?.bitrate).toBe(128);
  });

  it("flags a short preview and does not treat a matching duration as proof of identity", () => {
    expect(assessDuration(track, 30000)).toBe("mismatch");
    expect(assessDuration(track, 100500)).toBe("consistent");
    expect(assessDuration(track, null)).toBe("unknown");
  });

  it("accepts KuWo search responses without a top-level code", async () => {
    const transport = vi.fn(async () => encrypted({ data: { list: [{ rid: "123", name: "Test", artist: "Inala" }] } }));
    const results = await new CloudMusicClient(transport).search("kuwo", "test");
    expect(results[0]).toMatchObject({ source: "kuwo", id: "123", title: "Test", artists: ["Inala"] });
  });
});
