import styled from "@emotion/styled";
import { css } from "@emotion/react";
import Draggable from "react-draggable";
import Button from "metabase/core/components/Button";
import { color, lighten } from "metabase/lib/colors";

interface TableDraggableProps {
  enableCustomUserSelectHack?: boolean;
}

export const TableDraggable = styled(Draggable)<TableDraggableProps>`
  ${props =>
    props.enableCustomUserSelectHack &&
    css`
      .react-draggable-transparent-selection *::-moz-selection {
        all: inherit;
      }

      .react-draggable-transparent-selection *::selection {
        all: inherit;
      }
    `}
`;

export const HeaderCell = styled.div`
  color: ${color("text-medium")};

  &:hover {
    color: ${color("text-brand")};
  }
`;

export const ResizeHandle = styled.div`
  &:active {
    background-color: ${color("brand")};
  }

  &:hover {
    background-color: ${color("brand")};
  }
`;

export const ExpandButton = styled(Button)`
  border: 1px solid ${() => lighten("brand", 0.3)};
  padding: 0.125rem 0.25rem;
  border-radius: 0.25rem;
  color: ${color("brand")};
  margin-right: 0.5rem;
  margin-left: auto;

  &:hover {
    color: ${color("text-white")};
    background-color: ${color("brand")};
  }
`;
