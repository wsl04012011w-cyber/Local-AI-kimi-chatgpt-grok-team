import React from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';

const tick = String.fromCharCode(96);

function inlineNodes(source: string, prefix: string): React.ReactNode[] {
  const pattern = new RegExp('(\*\*[^*]+\*\*|__[^_]+__|~~[^~]+~~|==[^=]+==|' +
    tick + '[^' + tick + ']+' + tick + '|\*[^*\n]+\*|_[^_\n]+_)', 'g');
  const result: React.ReactNode[] = [];
  let cursor = 0;
  let match: RegExpExecArray | null;
  let n = 0;
  while ((match = pattern.exec(source)) !== null) {
    if (match.index > cursor) result.push(source.slice(cursor, match.index));
    const token = match[0];
    const key = prefix + '-' + n++;
    if (token.startsWith('**') || token.startsWith('__')) {
      result.push(<Text key={key} style={styles.bold}>{inlineNodes(token.slice(2, -2), key)}</Text>);
    } else if (token.startsWith('~~')) {
      result.push(<Text key={key} style={styles.strike}>{token.slice(2, -2)}</Text>);
    } else if (token.startsWith('==')) {
      result.push(<Text key={key} style={styles.highlight}>{token.slice(2, -2)}</Text>);
    } else if (token.startsWith(tick)) {
      result.push(<Text key={key} style={styles.inlineCode}>{token.slice(1, -1)}</Text>);
    } else {
      result.push(<Text key={key} style={styles.italic}>{token.slice(1, -1)}</Text>);
    }
    cursor = match.index + token.length;
  }
  if (cursor < source.length) result.push(source.slice(cursor));
  return result;
}

function cellsOf(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim());
}

function isSeparator(line: string): boolean {
  return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);
}

function ChatMarkdown({content, thinking = false}: {content: string; thinking?: boolean}) {
  const lines = content.replace(/\r\n/g, '\n').split('\n');
  const blocks: React.ReactNode[] = [];
  let i = 0;
  let key = 0;
  const structural = (line: string) =>
    line.startsWith(tick + tick + tick) ||
    /^\s{0,3}#{1,6}\s/.test(line) ||
    /^\s*>/.test(line) ||
    /^\s*([-*+]\s+|\d+[.)]\s+)/.test(line);

  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }

    if (line.trimStart().startsWith(tick + tick + tick)) {
      const language = line.trim().slice(3).trim();
      i++;
      const code: string[] = [];
      while (i < lines.length && !lines[i].trimStart().startsWith(tick + tick + tick)) code.push(lines[i++]);
      if (i < lines.length) i++;
      blocks.push(
        <View key={'code-' + key++} style={styles.codeCard}>
          {!!language && <Text style={styles.language}>{language}</Text>}
          <ScrollView horizontal nestedScrollEnabled>
            <Text selectable style={styles.codeText}>{code.join('\n')}</Text>
          </ScrollView>
        </View>,
      );
      continue;
    }

    if (i + 1 < lines.length && line.includes('|') && isSeparator(lines[i + 1])) {
      const headers = cellsOf(line);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) rows.push(cellsOf(lines[i++]));
      blocks.push(
        <ScrollView key={'table-' + key++} horizontal nestedScrollEnabled>
          <View style={styles.table}>
            <View style={[styles.tableRow, styles.tableHeader]}>
              {headers.map((cell, c) => (
                <View key={'h-' + c} style={styles.tableCell}>
                  <Text style={styles.tableHeaderText}>{inlineNodes(cell, 'th-' + key + '-' + c)}</Text>
                </View>
              ))}
            </View>
            {rows.map((row, ri) => (
              <View key={'r-' + ri} style={styles.tableRow}>
                {headers.map((_, c) => (
                  <View key={'c-' + c} style={styles.tableCell}>
                    <Text style={styles.tableText}>{inlineNodes(row[c] || '', 'td-' + key + '-' + ri + '-' + c)}</Text>
                  </View>
                ))}
              </View>
            ))}
          </View>
        </ScrollView>,
      );
      continue;
    }

    const heading = line.match(/^\s{0,3}(#{1,3})\s+(.*)$/);
    if (heading) {
      const headingStyle = heading[1].length === 1 ? styles.h1 : heading[1].length === 2 ? styles.h2 : styles.h3;
      blocks.push(<Text key={'heading-' + key++} style={headingStyle}>{inlineNodes(heading[2], 'heading-' + key)}</Text>);
      i++;
      continue;
    }

    if (/^\s*>/.test(line)) {
      const quote: string[] = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) quote.push(lines[i++].replace(/^\s*>\s?/, ''));
      blocks.push(<Text key={'quote-' + key++} style={styles.quote}>{inlineNodes(quote.join('\n'), 'quote-' + key)}</Text>);
      continue;
    }

    const list = line.match(/^\s*([-*+]\s+|\d+[.)]\s+)(.*)$/);
    if (list) {
      const ordered = /^\d/.test(list[1]);
      while (i < lines.length) {
        const current = lines[i].match(/^\s*([-*+]\s+|\d+[.)]\s+)(.*)$/);
        if (!current) break;
        blocks.push(
          <View key={'list-' + key++} style={styles.listRow}>
            <Text style={styles.bullet}>{ordered ? current[1].trim() : '•'}</Text>
            <Text style={styles.paragraph}>{inlineNodes(current[2], 'list-' + key)}</Text>
          </View>,
        );
        i++;
      }
      continue;
    }

    const paragraph = [line];
    i++;
    while (i < lines.length && lines[i].trim() && !structural(lines[i]) &&
      !(i + 1 < lines.length && lines[i].includes('|') && isSeparator(lines[i + 1]))) {
      paragraph.push(lines[i++]);
    }
    blocks.push(<Text key={'p-' + key++} selectable style={thinking ? styles.thinkingParagraph : styles.paragraph}>{inlineNodes(paragraph.join('\n'), 'p-' + key)}</Text>);
  }

  return <View style={styles.body}>{blocks}</View>;
}

const styles = StyleSheet.create({
  body: {gap: 9, flexShrink: 1},
  paragraph: {fontSize: 15, lineHeight: 23, color: '#F5F5F5', flexShrink: 1},
  thinkingParagraph: {fontSize: 12, lineHeight: 18, color: '#BDBDBD', flexShrink: 1},
  h1: {fontSize: 20, fontWeight: '700', color: '#FFFFFF', lineHeight: 27},
  h2: {fontSize: 18, fontWeight: '700', color: '#FFFFFF', lineHeight: 25},
  h3: {fontSize: 16, fontWeight: '700', color: '#FFFFFF', lineHeight: 23},
  bold: {fontWeight: '700', color: '#FFFFFF'},
  italic: {fontStyle: 'italic'},
  strike: {textDecorationLine: 'line-through', color: '#BDBDBD'},
  highlight: {backgroundColor: '#595959', color: '#FFFFFF'},
  inlineCode: {fontFamily: 'monospace', fontSize: 13, color: '#FFFFFF', backgroundColor: '#303030'},
  codeCard: {borderRadius: 10, borderWidth: 1, borderColor: '#333333', backgroundColor: '#080808', padding: 10, gap: 6},
  language: {fontSize: 10, textTransform: 'uppercase', color: '#A3A3A3', fontWeight: '600'},
  codeText: {fontFamily: 'monospace', fontSize: 12, lineHeight: 18, color: '#E5E5E5'},
  quote: {borderLeftWidth: 2, borderLeftColor: '#777777', paddingLeft: 10, color: '#BDBDBD', fontSize: 14, lineHeight: 21},
  listRow: {flexDirection: 'row', alignItems: 'flex-start', gap: 7},
  bullet: {fontSize: 14, lineHeight: 22, color: '#BDBDBD', width: 18},
  table: {borderRadius: 8, overflow: 'hidden', borderWidth: 1, borderColor: '#404040'},
  tableRow: {flexDirection: 'row', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#383838'},
  tableHeader: {backgroundColor: '#292929'},
  tableCell: {minWidth: 88, maxWidth: 190, paddingHorizontal: 8, paddingVertical: 7, borderRightWidth: StyleSheet.hairlineWidth, borderRightColor: '#383838'},
  tableHeaderText: {fontSize: 12, fontWeight: '700', color: '#FFFFFF'},
  tableText: {fontSize: 12, lineHeight: 17, color: '#E5E5E5'},
});

// Prior messages keep the same content during streaming; skip reparsing those blocks.
export default React.memo(ChatMarkdown);
