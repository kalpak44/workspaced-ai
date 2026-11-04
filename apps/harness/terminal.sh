# Loaded by /etc/profile for interactive Bash login shells, including the web terminal.
# Leave non-interactive agent commands and other shells alone.
if [ -n "${BASH_VERSION:-}" ] && [ "${TERM:-dumb}" != dumb ]; then
  case $- in
    *i*)
      PS1='\[\e[32m\]\u\[\e[0m\]:\[\e[34m\]\w\[\e[0m\]\$ '
      alias ls='ls --color=auto'
      ;;
  esac
fi
