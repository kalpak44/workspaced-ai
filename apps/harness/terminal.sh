# Loaded by /etc/profile for login shells, including the web terminal. /etc/profile resets
# PATH, so the home's own programs are put back: ~/.local/bin in front, the home's apk root
# behind the image's folders.
case ":$PATH:" in
  *":$HOME/.local/bin:"*) ;;
  *) PATH="$HOME/.local/bin:$PATH:$HOME/.local/alpine/usr/bin:$HOME/.local/alpine/bin:$HOME/.local/alpine/usr/sbin" ;;
esac
export PATH
# The prompt and colours are for interactive Bash only; other shells are left alone.
if [ -n "${BASH_VERSION:-}" ] && [ "${TERM:-dumb}" != dumb ]; then
  case $- in
    *i*)
      PS1='\[\e[32m\]\u\[\e[0m\]:\[\e[34m\]\w\[\e[0m\]\$ '
      alias ls='ls --color=auto'
      ;;
  esac
fi
