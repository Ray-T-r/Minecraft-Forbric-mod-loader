#!/usr/bin/env python3
"""Launch the installed profile as a dedicated server, preserving kernel arguments."""
import sys
from common import config, parser, run_java


def main():
    argument_parser = parser(__doc__)
    configuration = config(argument_parser.parse_args(), argument_parser)
    return run_java(configuration, server=True) if configuration else 0


if __name__ == '__main__':
    sys.exit(main())
