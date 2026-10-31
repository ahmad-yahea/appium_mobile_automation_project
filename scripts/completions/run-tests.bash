# Tab completion for scripts/run-tests.sh (bash).
#
# Install: add this line to ~/.bashrc (adjust the path if your checkout
# lives elsewhere), then open a new shell:
#
#   source ~/appium_mobile_automation_project/scripts/completions/run-tests.bash
#
# Completes -n/--repeat, -h/--help, the common -D properties, and - as the
# bare positional - every *Test class under src/test/java/automation/tests
# (discovered live, so new test classes show up automatically).

_run_tests_sh() {
    local cur prev
    cur=${COMP_WORDS[COMP_CWORD]}
    prev=${COMP_WORDS[COMP_CWORD-1]}

    case $prev in
        -n|--repeat)
            return
            ;;
    esac

    local tests=""
    if [[ -d src/test/java/automation/tests ]]; then
        tests=$(cd src/test/java/automation/tests && ls ./*Test.java 2>/dev/null | sed 's#.*/##; s#\.java$##')
    fi

    local opts="-n --repeat -p --parallel -h --help -Dtest= -Dretry.skip=true -Dretry.maxAttempts=1 -Dplatform=ios -Ddevice= -Dtest.forkCount=2 -Dandroid.devices= -Dfork.index="

    # shellcheck disable=SC2207  # word-split is intended; candidates have no spaces
    COMPREPLY=( $(compgen -W "${opts} ${tests}" -- "$cur") )
}

complete -o default -F _run_tests_sh run-tests.sh ./scripts/run-tests.sh scripts/run-tests.sh
