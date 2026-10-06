# MiniGit

MiniGit is a small, educational version control system written in plain Java 17. It re-implements the
core ideas of Git — content-addressable storage, a staging area, commits, branches and checkout — in
about 2,700 lines of readable code, with no dependencies other than JUnit for tests.

```text
$ minigit init
$ minigit add README.md
$ minigit commit -m "Initial commit"
[main 0603521] Initial commit
 1 file changed
```

## Features

| Area | Supported |
| --- | --- |
| Repository | `init` creates `.minigit/` (HEAD, config, index, objects/, refs/heads/, logs/) with a `main` branch |
| Object storage | SHA-256 content-addressable blobs and commits, stored at `objects/<2 chars>/<62 chars>`, immutable |
| Staging | `add <file>`, `add <dir>`, `add .` (recursive, ignores `.minigit`, stages deletions) |
| Status | staged / unstaged / untracked files; new, modified and deleted files |
| Commits | `commit -m` with author from config, parent link, timestamp and full snapshot |
| History | `log` and `log --oneline` following parent pointers |
| Branches | `branch`, `branch <name>` — a branch is just a pointer to a commit |
| Checkout | `checkout <branch>`, `checkout -b <name>`; refuses to overwrite uncommitted work |
| Diff | `diff` (working tree vs last commit), `diff --cached` (index vs last commit); unified line diff, binary detection |
| Config | `config user.name`, `config user.email`, `config --list` |
| UX | `help`, `help <command>`, `--version`, friendly `fatal: ...` errors (no stack traces) |

## Architecture

```text
Working Tree        files you edit
     ↓  minigit add
Index / Staging     .minigit/index        path → blob hash (+ size/mtime fingerprint)
     ↓  (blobs are written on add)
Object Store        .minigit/objects/…    blobs and commits, named by SHA-256
     ↓  minigit commit
Commit              parent + author + time + snapshot (path → blob hash)
     ↓
Branch              .minigit/refs/heads/<name>   contains one commit hash
     ↓
HEAD                .minigit/HEAD         "ref: refs/heads/<current branch>"
```

Package layout (`src/main/java/com/minigit`):

| Package | Responsibility |
| --- | --- |
| `cli` | `CommandHandler` (dispatch, exit codes), `CommandParser`/`ParsedArgs`, `Console`, the `Command` interface |
| `commands` | one class per command: `InitCommand`, `AddCommand`, `StatusCommand`, `CommitCommand`, `LogCommand`, `DiffCommand`, `BranchCommand`, `CheckoutCommand`, `ConfigCommand`, `HelpCommand` |
| `core` | `Repository`, `ObjectStore`, `Index`/`IndexEntry`, `Commit` (record), `Branch`, `RefStore`, `Config`, `WorkingTree`, `Stager`, `StatusReport` |
| `diff` | `DiffEngine` (LCS line diff) and `DiffResult` (unified output) |
| `hash` | `HashUtils` (SHA-256, streaming file hashing) |
| `storage` | `FileStore` — the only class that touches the file system (atomic writes, error translation) |
| `exceptions` | `MiniGitException` and friends — expected, user-facing failures |
| `utils` | `FileUtils`, `TimeUtils` |

## Requirements

* **JDK 17 or newer** (`java -version`)
* **Maven 3.8+** (`mvn -version`)

No other tools or libraries are needed. Works on Windows, Linux and macOS.

## Build

```bash
mvn clean package
```

This compiles the code, runs the 144 unit tests and produces `target/minigit.jar`.

## Run

The jar is self-contained:

```bash
java -jar target/minigit.jar --version
```

Convenience launchers are in `bin/`. Put that directory on your `PATH` to get a plain `minigit` command.

**Linux / macOS / Git Bash**

```bash
export PATH="$PWD/bin:$PATH"
minigit --version          # MiniGit version 1.0.0
```

**Windows (PowerShell)**

```powershell
$env:PATH = "$PWD\bin;$env:PATH"
minigit --version          # resolves to bin\minigit.cmd
```

**Windows (cmd)**

```bat
set PATH=%CD%\bin;%PATH%
minigit --version
```

## Example workflow

```bash
mkdir demo
cd demo

minigit init

minigit config user.name "Lavya"
minigit config user.email "lavya@example.com"

echo "Hello MiniGit" > README.md

minigit add README.md
minigit status

minigit commit -m "Initial commit"

minigit log

minigit branch feature
minigit checkout feature
```

A scripted 5-minute walkthrough is in [DEMO.md](DEMO.md).

## Command reference

```text
minigit init [<directory>]
minigit add <pathspec>...          file, directory, or . for everything
minigit status
minigit commit -m "message"
minigit log [--oneline]
minigit diff [--cached]
minigit branch [<name>]
minigit checkout <branch>
minigit checkout -b <name>
minigit config <key> [<value>]     e.g. user.name, user.email
minigit config --list
minigit help [<command>]
minigit --version
```

## How it works

### SHA-256

Every stored object is identified by the SHA-256 digest (`java.security.MessageDigest`) of its bytes,
printed as 64 lowercase hex characters. Same content → same hash; any change → a different hash. Files
are hashed in 8 KB chunks so large files are never loaded fully into memory.

### Object store

`ObjectStore` saves an object at `.minigit/objects/<first 2 hex chars>/<remaining 62>`. The two-character
directory keeps any single directory small. Objects are immutable: writing something that already exists
is a no-op, so identical files are stored once no matter how many commits use them. Writes go to a temp
file first and are then moved into place, so a crash never leaves a half-written object.

### Staging (the index)

`.minigit/index` has one line per staged file: `<blob hash> <size> <mtime> <path>`. `add` hashes the file,
stores the blob and records the entry. The index always holds *the snapshot the next commit will contain*.
Right after a commit it equals the commit's snapshot, so "the staging area is empty" simply means
"index == last commit". Size + mtime are remembered so `status` and `add` can skip re-hashing files that
have not been touched (entries modified within two seconds of being saved are re-hashed to avoid stale
fingerprints — the same "racy index" guard real Git uses).

### Commits

A commit is a text object:

```text
parent <hash>               (absent for the first commit)
author Lavya <lavya@example.com>
timestamp 1790000000000
tree 2
<blob hash> README.md
<blob hash> src/Main.java

Initial commit
```

Its ID is the SHA-256 of that text, so it covers the snapshot, the parent, the author, the time and the
message. Changing any of them yields a different commit. `commit` refuses to run with nothing staged.
(Unlike real Git, MiniGit stores the snapshot as one flat path → blob list instead of nested tree objects.)

### Branches and HEAD

`.minigit/refs/heads/<name>` contains a single commit hash. Creating a branch writes one tiny file — no
files are copied. `HEAD` holds `ref: refs/heads/<name>`; a commit moves the branch HEAD points to.
`.minigit/logs/HEAD` records commits and checkouts.

### Checkout

Checkout compares the current commit's snapshot with the target's and touches **only the files that
differ**: it deletes files that do not exist on the target (pruning empty directories), restores the
others from the object store, rewrites those index entries, and points HEAD at the branch. Before
changing anything it checks every file it is about to touch; if one has staged changes, unstaged edits,
or an untracked file in the way, it aborts with
`fatal: uncommitted changes would be overwritten by checkout` and lists the files. Changes to files the
two branches share are carried over, exactly like Git.

### Integrity and safety

* Every object read is re-hashed; a damaged object is reported (`object … is corrupt`) instead of being used.
* Paths read from commits and the index are validated: no `..`, absolute, backslash or `.minigit` segments, so a
  hand-crafted commit cannot make `checkout` write outside the working tree. Writes through symbolic links that leave
  the repository are refused.
* `commit` refuses to record a snapshot whose blobs are missing from the object store.
* `checkout` verifies every blob it must restore *before* touching any file, refuses on file/directory clashes, and
  rolls back already-applied changes if a write still fails half-way.

### Diff

`DiffEngine` computes a line-based diff with the longest-common-subsequence algorithm, after trimming
identical leading/trailing lines. Output is a unified diff with three lines of context and `@@` hunk
headers. Content containing a NUL byte is treated as binary and reported as `Binary files differ`.
`diff` compares the working tree to the last commit; `diff --cached` compares the index to it.

## Tests

```bash
mvn clean test
```

144 JUnit 5 tests cover hashing, the object store, repository setup, config, staging, commits, log,
branches, checkout safety, status, the diff engine and CLI error handling. Most tests drive the real
command handler against a temporary directory.

## Limitations

MiniGit is an educational project and **not a replacement for Git**. Notably:

* No merge, rebase, tags, remotes, stash, `reset`, or detached HEAD.
* No `.gitignore`-style ignore rules (only `.minigit` directories, at any depth, are ignored). A nested
  MiniGit repository is not treated as a submodule: its files (but not its `.minigit`) are tracked by the parent.
* Symbolic links and Windows junctions are never followed or tracked.
* **Windows: non-ANSI characters typed on the command line** (for example `✓` or Cyrillic in `-m "..."`) are
  replaced by `?` by the Java runtime before MiniGit sees them. File names and file contents are unaffected
  (they are read from disk, not from the command line), as is anything stored through the API.
* File permissions (including the executable bit) are not tracked; restored files get default permissions.
* On case-insensitive file systems (Windows, default macOS), two tracked paths that differ only in case cannot coexist,
  and a checkout that changes only a file's letter case may be refused as a conflict. Branch names are always
  compared case-insensitively for collisions, so `Main` and `main` cannot both exist.
* Every commit stores a flat full snapshot list; there are no packfiles, delta compression or garbage collection.
* Empty directories and file permissions are not tracked; file names containing line breaks are rejected.
* The diff cannot show line-ending or trailing-newline changes line by line; it reports them as
  "Files differ only in line endings, trailing newline or character encoding".
* Branch names are limited to letters, digits, `.`, `_` and `-` (no `/`).
* Commands that modify the repository (`add`, `commit`, `branch`, `checkout`, `config`) take an operating-system
  file lock on `.minigit/lock`, so simultaneous runs queue up (up to 15 s) instead of overwriting each other. The OS
  drops the lock if a process crashes, so there is never a stale lock to clean up; the empty `lock` file stays in place.
  Read-only commands (`status`, `log`, `diff`) do not lock and may show a momentary mix of old and new state while
  another command runs. (OS file locks are unreliable on some network file systems.)
* Speed: every object is written to a temp file and renamed into place so a crash can never leave a half-written
  object. On a Windows machine with real-time antivirus that costs about 4 ms per file, so staging or switching
  5,000 files takes roughly 20 seconds (a few hundred files take about a second). Linux/macOS are expected to be
  faster but have not been measured.
* A missing `.minigit/index` is treated as repository damage (an error), not as "nothing staged".
* Do not point it at precious data without a backup — it is a learning tool.

## License

Use it freely for learning.
