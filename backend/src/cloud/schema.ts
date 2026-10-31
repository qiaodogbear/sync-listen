import { z } from "zod";

export const cloudSourceSchema = z.enum(["netease", "qq", "kugou", "kuwo"]);
export const cloudTrackSchema = z.object({
  source: cloudSourceSchema, id: z.string().min(1).max(128), title: z.string().trim().min(1).max(500),
  artists: z.array(z.string().max(500)).max(30), album: z.string().max(500).default(""),
  durationMs: z.number().int().nonnegative().nullable().default(null), mediaId: z.string().max(128).default(""),
  songType: z.number().int().min(0).max(1000).default(0),
  availableQualities: z.array(z.string().max(40)).max(20).default([]), coverUrl: z.string().max(2048).default(""),
});
export const cloudReferenceSchema = z.object({
  track: cloudTrackSchema, format: z.string().max(40), bitrate: z.number().int().positive().nullable().default(null),
  resolvedTitle: z.string().max(500).default(""), resolvedArtist: z.string().max(500).default(""),
});
