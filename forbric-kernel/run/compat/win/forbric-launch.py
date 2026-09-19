#!/usr/bin/env python3
"""Launch the installed client profile through a Java argument file."""
import sys
from common import config, parser, run_java


def main():
    argument_parser = parser(__doc__)
    configuration = config(argument_parser.parse_args(), argument_parser)
    return run_java(configuration) if configuration else 0


if __name__ == '__main__':
    sys.exit(main())
