# HomeLight

HomeLight reconciles configured source paths with durable storage targets. Its desired state is a real target directory and a source symlink to that directory.

## Reconciliation

**Converged relocation**:
A relocation whose target is a real directory and whose source is the correct symlink to it.
_Avoid_: completed move, migrated state

**Unchanged relocation**:
An intentionally non-converged relocation left untouched by the `leave-unchanged` decision.
_Avoid_: no-op, preserved relocation

**Adopt target**:
The decision that an already-existing target directory is authoritative. It requires a separate disposition for an already-existing source directory.
_Avoid_: adopt source, move

**Staging directory**:
A HomeLight-owned directory on the target filesystem used to prepare targets for atomic publication. Each target has its own staged copy and lock file there.
_Avoid_: temporary directory, transaction journal
