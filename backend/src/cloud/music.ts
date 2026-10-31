import { createDecipheriv, randomInt } from "node:crypto";

export type MusicSource = "netease" | "qq" | "kugou" | "kuwo";
export type CloudTrack = {
  source: MusicSource;
  id: string;
  title: string;
  artists: string[];
  album: string;
  durationMs: number | null;
  mediaId: string;
  songType: number;
  availableQualities?: string[];
  coverUrl?: string;
};
export type AudioCandidate = { url: string; bitrate: number | null; format: string };
export type CloudTrackReference = {
  track: CloudTrack;
  format: string;
  bitrate: number | null;
  resolvedTitle: string;
  resolvedArtist: string;
};
export type Resolution = {
  track: CloudTrack;
  candidates: AudioCandidate[];
  resolvedTitle: string;
  resolvedArtist: string;
  method: string;
};
// Provider response shapes vary by endpoint and are checked at their boundaries.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = Record<string, any>;
type Transport = (url: string, init: RequestInit) => Promise<Response>;

const gateway = "http://api-music.1fooai.com/api/musicApi.php?app=txmusic";
const qqEndpoint = "https://u.y.qq.com/cgi-bin/musicu.fcg";
const searchService = "music.search.SearchCgiService";

export class CloudMusicError extends Error {}

// The service returns encrypted text. These constants identify the APK protocol;
// they do not authenticate the server or protect the HTTP connection.
export function decryptGatewayResponse(text: string): Json {
  try {
    const decipher = createDecipheriv("aes-256-cbc", Buffer.from("xSdddRXNm0P5689HI34TqJNzDb666666"), Buffer.from("dgjsJhf58aFlOk15"));
    const plain = Buffer.concat([decipher.update(Buffer.from(text.trim(), "base64")), decipher.final()]);
    return JSON.parse(plain.toString("utf8")) as Json;
  } catch {
    throw new CloudMusicError("Invalid gateway response");
  }
}

function string(value: unknown): string {
  return typeof value === "string" || typeof value === "number" ? String(value) : "";
}
function number(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : null;
}
function httpUrl(value: unknown): string | null {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value);
    return (url.protocol === "https:" || url.protocol === "http:") && !url.username && !url.password ? value : null;
  } catch { return null; }
}

export class CloudMusicClient {
  constructor(private readonly transport: Transport = fetch) {}

  private async request(url: string, init: RequestInit = {}): Promise<string> {
    try {
      const response = await this.transport(url, { ...init, signal: AbortSignal.timeout(20_000) });
      if (!response.ok) throw new CloudMusicError(`Music service HTTP ${response.status}`);
      return await response.text();
    } catch (error) {
      if (error instanceof CloudMusicError) throw error;
      throw new CloudMusicError("Music service unavailable or timed out");
    }
  }

  private async json(url: string, body?: Json): Promise<Json> {
    const init: RequestInit = body ? { method: "POST", headers: { "Content-Type": "application/json", "Referer": "https://y.qq.com/" }, body: JSON.stringify(body) } : {};
    try { return JSON.parse(await this.request(url, init)) as Json; }
    catch (error) {
      if (error instanceof CloudMusicError) throw error;
      throw new CloudMusicError("Invalid music service response");
    }
  }

  private async gateway(type: string, path: string): Promise<Json> {
    const text = await this.request(gateway, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ musicType: type, apiParam: path }) });
    return decryptGatewayResponse(text);
  }

  async search(source: MusicSource, keyword: string, page = 1): Promise<CloudTrack[]> {
    if (!["netease", "qq", "kugou", "kuwo"].includes(source)) throw new CloudMusicError("Unsupported music source");
    if (!keyword.trim() || keyword.length > 200 || !Number.isSafeInteger(page) || page < 1 || page > 10000) throw new CloudMusicError("Invalid search");
    const q = encodeURIComponent(keyword);
    let rows: Json[];
    if (source === "qq") {
      const data = await this.json(qqEndpoint, { comm: { ct: 19, cv: 1845 }, [searchService]: { method: "DoSearchForQQMusicDesktop", module: searchService, param: { query: keyword, num_per_page: 30, page_num: page } } });
      if (data.code !== 0 || data[searchService]?.code !== 0) throw new CloudMusicError("QQ search failed");
      rows = data[searchService]?.data?.body?.song?.list;
    } else if (source === "kugou") {
      const data = await this.json(`https://songsearch.kugou.com/song_search_v2?platform=AndroidFilter&iscorrection=1&keyword=${q}&page=${page}&hifiquality=0&pagesize=100&PrivilegeFilter=0`);
      if (data.status !== 1) throw new CloudMusicError("KuGou search failed");
      rows = data.data?.lists;
    } else {
      const data = await this.gateway(source === "netease" ? "wy" : "kw", source === "netease" ? `/cloudsearch?keywords=${q}&offset=${(page - 1) * 30}` : `/kuwo/search/searchMusicBykeyWord?key=${q}&pn=${page}`);
      if (source === "netease" ? data.code !== 200 : data.code !== undefined && data.code !== 200) throw new CloudMusicError("Music search failed");
      rows = source === "netease" ? data.result?.songs : data.data?.list;
    }
    if (!Array.isArray(rows)) throw new CloudMusicError("Invalid search response");
    const validRows = rows.filter(row => row && typeof row === "object");
    return validRows.map(row => {
      if (source === "qq") return { source, id: string(row.mid), title: string(row.title ?? row.name), artists: Array.isArray(row.singer) ? row.singer.map((x: Json) => string(x.name)) : [], album: string(row.album?.name), durationMs: number(row.interval) === null ? null : Number(row.interval) * 1000, mediaId: string(row.file?.media_mid), songType: number(row.type) ?? 0 };
      if (source === "kugou") return { source, id: string(row.FileHash), title: string(row.SongName), artists: [string(row.SingerName)], album: string(row.AlbumName), durationMs: number(row.Duration) === null ? null : Number(row.Duration) * 1000, mediaId: "", songType: 0 };
      if (source === "kuwo") return { source, id: string(row.rid), title: string(row.name), artists: [string(row.artist)], album: string(row.album), durationMs: number(row.duration) === null ? null : Number(row.duration) * 1000, mediaId: "", songType: 0 };
      return { source, id: string(row.id), title: string(row.name), artists: Array.isArray(row.ar) ? row.ar.map((x: Json) => string(x.name)) : [], album: string(row.al?.name), durationMs: number(row.dt), mediaId: "", songType: 0 };
    }).map((track, index) => {
      const row = validRows[index]!;
      const fields: Array<[string, unknown]> = source === "qq"
        ? [["mp3-128", row.file?.size_128mp3], ["mp3-320", row.file?.size_320mp3], ["flac", row.file?.size_flac], ["aac-192", row.file?.size_192aac]]
        : source === "kugou" ? [["standard", row.FileSize], ["high", row.HQFileSize], ["lossless", row.SQFileSize]]
        : source === "netease" ? [["standard", row.l?.size], ["higher", row.m?.size], ["high", row.h?.size], ["lossless", row.sq?.size], ["hires", row.hr?.size]] : [];
      const coverUrl = source === "netease" ? string(row.al?.picUrl) : source === "kuwo" ? string(row.albumpic) : "";
      return { ...track, coverUrl, availableQualities: fields.filter(([, value]) => (number(value) ?? 0) > 0).map(([label]) => label) };
    }).filter(track => track.id && track.title);
  }

  async lyrics(track: CloudTrack): Promise<{ text: string }> {
    if (track.source !== "netease") throw new CloudMusicError("Lyrics unavailable for this source");
    if (!/^[0-9]{1,32}$/.test(track.id)) throw new CloudMusicError("Invalid track identifier");
    const data = await this.gateway("wy", `/lyric?id=${encodeURIComponent(track.id)}`);
    if (data.code !== 200) throw new CloudMusicError("Lyrics request failed");
    return { text: string(data.lrc?.lyric).slice(0, 200_000) };
  }

  async resolve(track: CloudTrack): Promise<Resolution> {
    if (!["netease", "qq", "kugou", "kuwo"].includes(track.source)) throw new CloudMusicError("Unsupported music source");
    if (!track.id || track.id.length > 128) throw new CloudMusicError("Invalid track identifier");
    if (track.source === "qq") return this.resolveQq(track);
    const id = encodeURIComponent(track.id);
    const params = track.source === "netease" ? ["wy2", `/song/url/v2?id=${id}`] : track.source === "kugou" ? ["kg", `/song/urls?hash=${id}`] : ["kw", `/song/urls?rid=${id}`];
    const parse = async (type: string, path: string): Promise<Resolution> => {
      const data = await this.gateway(type, path);
      if (data.code !== 200 || !Array.isArray(data.data)) throw new CloudMusicError("Music resolution failed");
      const candidates = data.data.flatMap((row: Json) => {
        const url = httpUrl(row?.url);
        return url ? [{ url, bitrate: number(row.br), format: string(row.format ?? row.type) }] : [];
      });
      return { track, candidates, resolvedTitle: string(data.gm ?? data.name), resolvedArtist: string(data.gs ?? data.arname), method: path.split("?")[0] ?? "" };
    };
    try {
      const result = await parse(params[0]!, params[1]!);
      if (result.candidates.length) return result;
    } catch (error) { if (track.source !== "netease") throw error; }
    if (track.source === "netease") {
      const fallback = await parse("wy", `/song/url/v1?id=${id}&level=standard`);
      if (fallback.candidates.length) return fallback;
    }
    throw new CloudMusicError("No audio address returned");
  }

  private async resolveQq(track: CloudTrack): Promise<Resolution> {
    if (!/^[a-z0-9]+$/i.test(track.id) || !/^[a-z0-9]+$/i.test(track.mediaId)) throw new CloudMusicError("QQ song and media identifiers are required");
    const formats = [["M500", ".mp3", 128, "mp3"], ["M800", ".mp3", 320, "mp3"], ["F000", ".flac", null, "flac"], ["C400", ".m4a", null, "m4a"]] as const;
    const filenames = formats.map(([prefix, suffix]) => prefix + track.mediaId + suffix);
    const guid = String(randomInt(1_000_000_000, 2_147_483_647));
    const data = await this.json(qqEndpoint, { req_0: { module: "vkey.GetVkeyServer", method: "CgiGetVkey", param: { filename: filenames, guid, songmid: filenames.map(() => track.id), songtype: filenames.map(() => track.songType), uin: "0", loginflag: 1, platform: "20" } }, comm: { uin: 0, format: "json", ct: 19, cv: 0 } });
    if (data.code !== 0 || data.req_0?.code !== 0) throw new CloudMusicError("QQ resolution failed");
    const result = data.req_0.data;
    const servers = Array.isArray(result?.sip) ? result.sip.map(httpUrl).filter((x: string | null): x is string => x !== null).sort((a: string, b: string) => Number(b.startsWith("https:")) - Number(a.startsWith("https:"))) : [];
    const candidates: AudioCandidate[] = [];
    if (servers.length && Array.isArray(result?.midurlinfo)) for (const row of result.midurlinfo) {
      const index = filenames.indexOf(row.filename);
      if (row.songmid !== track.id || row.result !== 0 || index < 0 || typeof row.purl !== "string" || !row.purl || row.purl.startsWith("/") || row.purl.startsWith("\\") || /^[a-z][a-z0-9+.-]*:/i.test(row.purl)) continue;
      const url = new URL(row.purl, servers[0]);
      if (url.origin !== new URL(servers[0]).origin) continue;
      const format = formats[index]!;
      candidates.push({ url: url.toString(), bitrate: format[2], format: format[3] });
    }
    if (!candidates.length) throw new CloudMusicError("QQ returned no exact-track address");
    return { track, candidates, resolvedTitle: "", resolvedArtist: "", method: "qq-direct-vkey" };
  }
}

export function orderCandidates(candidates: AudioCandidate[], quality: "server" | "highest" | "lowest"): AudioCandidate[] {
  if (quality === "server") return [...candidates];
  return [...candidates].sort((a, b) => a.bitrate === null ? (b.bitrate === null ? 0 : 1) : b.bitrate === null ? -1 : quality === "highest" ? b.bitrate - a.bitrate : a.bitrate - b.bitrate);
}

export function assessDuration(track: CloudTrack, actualDurationMs: number | null): "unknown" | "consistent" | "mismatch" {
  if (track.durationMs === null || track.durationMs <= 0 || actualDurationMs === null || !Number.isFinite(actualDurationMs) || actualDurationMs <= 0) return "unknown";
  return Math.abs(track.durationMs - actualDurationMs) > Math.max(3000, track.durationMs * 0.03) ? "mismatch" : "consistent";
}
