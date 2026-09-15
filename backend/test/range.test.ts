import { describe, expect, it } from "vitest";
import { parseByteRange } from "../src/tracks/range.js";

describe("single byte ranges", () => {
  it.each([
    ["bytes=0-2", { start: 0, end: 2 }], ["bytes=5-", { start: 5, end: 9 }],
    ["bytes=-3", { start: 7, end: 9 }], ["bytes=0-99", { start: 0, end: 9 }],
    ["bytes=10-", null], ["bytes=5-1", null], ["bytes=-0", null],
    ["bytes=0-1,4-5", null], ["bytes=9007199254740993-", null], ["junkbytes=1-", null],
  ])("parses %s", (header, expected) => expect(parseByteRange(header as string, 10)).toEqual(expected));
});
