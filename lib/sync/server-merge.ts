export function resolveServerWrite(existingUpdatedAt: number | undefined, incomingUpdatedAt: number): boolean {
  if (existingUpdatedAt === undefined) return true;
  return incomingUpdatedAt >= existingUpdatedAt;
}
