# macos-admin-add

A small Rust CLI for macOS that reads **one user name from standard input** and adds that
user to the local `admin` group — the group macOS treats as "administrator", i.e. the one
that grants `sudo` and the padlock in System Settings.

Built and tested for **macOS Ventura 13 on Intel (x86_64)**; the code is plain `std` and
works the same on Apple Silicon.

## Usage

```
echo alice | sudo macos-admin-add
sudo macos-admin-add            # prompts on stderr when stdin is a terminal
```

It must run as root — adding a member to `admin` is a privileged directory-service change,
and the tool does nothing to work around that. It is idempotent: a user who is already an
admin is reported and left alone.

```
macos-admin-add --help
macos-admin-add --version
```

### Exit codes

| Code | Meaning |
|-----:|---------|
| 0 | the user is in the `admin` group (added now, or already a member) |
| 1 | bad usage or malformed input |
| 2 | not running as root |
| 3 | no such user on this system |
| 4 | the group change failed or could not be verified |
| 5 | a required system tool could not be run (or this is not macOS) |

## What it actually does

1. Refuses to continue unless `geteuid() == 0`.
2. Reads the first line of stdin and validates it: ASCII letters, digits, `.`, `_`, `-`,
   non-empty, no leading `-`, at most 244 characters.
3. Confirms the account exists (`/usr/bin/id -u <name>`).
4. Checks current membership (`/usr/sbin/dseditgroup -o checkmember -m <name> admin`).
5. Adds the user (`/usr/sbin/dseditgroup -o edit -a <name> -t user admin`).
6. Re-checks membership, so a silent failure is still reported as one.

The name is taken from stdin rather than argv so it does not appear in `ps` output. Every
helper is invoked by absolute path, with a cleared environment and no shell, so the name is
passed as a single argument and can never be interpreted as a command.

## Build

On the Ventura/Intel machine itself:

```
cargo build --release
./target/release/macos-admin-add --version
```

Cross-compiling from Apple Silicon:

```
rustup target add x86_64-apple-darwin
MACOSX_DEPLOYMENT_TARGET=13.0 cargo build --release --target x86_64-apple-darwin
```

Install it somewhere only root can write, since it is run with `sudo`:

```
sudo install -o root -g wheel -m 0755 target/release/macos-admin-add /usr/local/sbin/
```

Do **not** make it setuid root; it is meant to be reached through `sudo`, so that
authentication and the sudo log stay in place.

## Tests

```
cargo test     # input-validation tests; no system state is touched
```
