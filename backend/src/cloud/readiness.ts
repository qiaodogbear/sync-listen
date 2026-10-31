import { randomInt } from "node:crypto";
import { AppError } from "../errors.js";

export class CloudReadiness {
  private generation = randomInt(2 ** 40, 2 ** 41);
  private trackId: string | null = null;
  private committed = false;
  private playAfterReady = true;
  private readonly members = new Map<string, boolean | null>();

  copy() {
    const copy = new CloudReadiness();
    copy.generation = this.generation;
    copy.trackId = this.trackId;
    copy.committed = this.committed;
    copy.playAfterReady = this.playAfterReady;
    for (const [user, ready] of this.members) copy.members.set(user, ready);
    return copy;
  }

  begin(trackId: string, members: string[], playAfterReady = true) {
    this.generation++;
    this.trackId = trackId;
    this.committed = false;
    this.playAfterReady = playAfterReady;
    this.members.clear();
    for (const member of members) this.members.set(member, null);
    return this.snapshot()!;
  }

  cancel() {
    this.generation++;
    this.trackId = null;
    this.committed = false;
    this.members.clear();
  }

  changeMember(userId: string, connected: boolean) {
    if (this.trackId && !this.committed) {
      if (connected && !this.members.has(userId)) this.members.set(userId, null);
      if (!connected) this.members.delete(userId);
    }
    return this.snapshot();
  }

  report(generation: number, trackId: string, userId: string, ready: boolean) {
    if (this.committed || generation !== this.generation || trackId !== this.trackId || !this.members.has(userId)) {
      throw new AppError(409, "PREPARATION_STALE", "准备状态已过期");
    }
    this.members.set(userId, ready);
    return this.snapshot()!;
  }

  commit(generation: number, force: boolean) {
    if (!this.trackId || this.committed || generation !== this.generation || (!force && [...this.members.values()].some(value => value !== true))) {
      throw new AppError(409, "PREPARATION_NOT_READY", "成员尚未准备好或准备状态已过期");
    }
    this.committed = true;
    return this.snapshot()!;
  }

  snapshot() {
    if (!this.trackId) return null;
    const entries = [...this.members.entries()];
    return { generation: this.generation, trackId: this.trackId, waiting: entries.filter(([, ready]) => ready !== true).map(([user]) => user),
      failed: entries.filter(([, ready]) => ready === false).map(([user]) => user), committed: this.committed, playAfterReady: this.playAfterReady };
  }
}
