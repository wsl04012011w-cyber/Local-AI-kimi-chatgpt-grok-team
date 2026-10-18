import React, {useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  Animated,
  FlatList,
  KeyboardAvoidingView,
  Modal,
  NativeEventEmitter,
  NativeModules,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import ChatMarkdown from './ChatMarkdown';

type ChatMessage = {
  id: number;
  role: 'user' | 'assistant';
  content: string;
  thinkingContent: string;
  isStreaming: boolean;
  isThinking: boolean;
  showThinking: boolean;
};

type InferenceSettings = {
  nCtx: number;
  nThreads: number;
  nThreadsBatch: number;
  flashAttn: boolean;
  effectiveFlashAttn: boolean;
  kvTypeK: number;
  kvTypeV: number;
  kvTypeKLabel: string;
  kvTypeVLabel: string;
  useMmap: boolean;
  useMlock: boolean;
  reuseCache: boolean;
};

type GenerationStats = {
  reusedTokens: number;
  promptTokens: number;
  generatedTokens: number;
  promptMs: number;
  generationMs: number;
  genTokensPerSecond: number;
} | null;

type NativeState = {
  messages: ChatMessage[];
  modelStatus: 'notLoaded' | 'loading' | 'loaded' | 'error';
  modelName: string;
  modelError: string;
  isGenerating: boolean;
  generationState: string;
  importing: boolean;
  importName: string;
  settings: InferenceSettings;
  stats: GenerationStats;
};

const bridge = NativeModules.LocalAI;
const emitter = new NativeEventEmitter(bridge);
const KV_OPTIONS = [
  {code: 0, label: 'F16'},
  {code: 1, label: 'Q8_0'},
  {code: 2, label: 'Q5_1'},
  {code: 3, label: 'Q5_0'},
  {code: 4, label: 'Q4_1'},
  {code: 5, label: 'Q4_0'},
];

const DEFAULT_SETTINGS: InferenceSettings = {
  nCtx: 4096,
  nThreads: 4,
  nThreadsBatch: 4,
  flashAttn: false,
  effectiveFlashAttn: false,
  kvTypeK: 0,
  kvTypeV: 0,
  kvTypeKLabel: 'F16',
  kvTypeVLabel: 'F16',
  useMmap: true,
  useMlock: false,
  reuseCache: true,
};

const DEFAULT_STATE: NativeState = {
  messages: [],
  modelStatus: 'notLoaded',
  modelName: '',
  modelError: '',
  isGenerating: false,
  generationState: 'idle',
  importing: false,
  importName: '',
  settings: DEFAULT_SETTINGS,
  stats: null,
};

export default function App() {
  const [state, setState] = useState<NativeState>(DEFAULT_STATE);
  const [draft, setDraft] = useState('');
  const [notice, setNotice] = useState('');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const listRef = useRef<FlatList<ChatMessage>>(null);

  useEffect(() => {
    const stateSubscription = emitter.addListener('LocalAIState', (next: Object) => {
      // React Native 0.87's strict event API types payloads as Object.
      setState(next as NativeState);
    });
    const noticeSubscription = emitter.addListener(
      'LocalAINotice',
      (event: {message?: string}) => setNotice(event.message || ''),
    );

    bridge.getInitialState()
      .then((initial: NativeState) => setState(initial))
      .catch((error: unknown) => {
        const message = error instanceof Error ? error.message : 'Não foi possível iniciar o app.';
        setNotice(message);
      });

    return () => {
      stateSubscription.remove();
      noticeSubscription.remove();
    };
  }, []);

  const modelReady = state.modelStatus === 'loaded';
  const [inputFocused, setInputFocused] = useState(false);
  const [stopRequested, setStopRequested] = useState(false);
  const [thinkingModeOpen, setThinkingModeOpen] = useState(false);
  const pendingSendRef = useRef<string | null>(null);
  const shimmer = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (draft.length > 0 || inputFocused) {
      shimmer.stopAnimation();
      shimmer.setValue(0);
      return;
    }
    const animation = Animated.loop(
      Animated.sequence([
        Animated.timing(shimmer, {toValue: 1, duration: 1000, useNativeDriver: false}),
        Animated.timing(shimmer, {toValue: 0, duration: 0, useNativeDriver: false}),
      ]),
    );
    animation.start();
    return () => animation.stop();
  }, [draft, inputFocused, shimmer]);

  useEffect(() => {
    if (!state.isGenerating) {
      setStopRequested(false);
      const pending = pendingSendRef.current;
      if (pending && modelReady) {
        pendingSendRef.current = null;
        bridge.sendMessage(pending);
      }
    }
  }, [state.isGenerating, modelReady]);

  const generating = state.isGenerating && !stopRequested;

  const send = () => {
    const text = draft.trim();
    if (!text || !modelReady) return;
    if (state.isGenerating) {
      if (stopRequested) {
        pendingSendRef.current = text;
        setDraft('');
      }
      return;
    }
    setDraft('');
    bridge.sendMessage(text);
  };

  const renderMessage = ({item}: {item: ChatMessage}) => {
    const isUser = item.role === 'user';
    return (
      <View style={[styles.messageRow, isUser ? styles.userRow : styles.assistantRow]}>
        <View style={[styles.messageCard, isUser ? styles.userCard : styles.assistantCard]}>
          {!isUser && (item.isThinking || !!item.thinkingContent) && (
            <View style={styles.thinkingSection}>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={item.showThinking ? 'Recolher thinking' : 'Expandir thinking'}
                style={styles.thinkingHeader}
                onPress={() => bridge.toggleThinking(item.id)}>
                <Text style={styles.thinkingBrain}>🧠</Text>
                <Text style={styles.thinkingLabel}>{item.isThinking ? 'thinking…' : 'thinking'}</Text>
                <Text style={styles.thinkingArrow}>{item.showThinking ? '⌃' : '⌄'}</Text>
              </Pressable>
              <View style={styles.thinkingBox}>
                {item.showThinking ? (
                  <ChatMarkdown content={item.thinkingContent || 'O modelo está pensando…'} thinking />
                ) : (
                  <Text numberOfLines={2} style={styles.thinkingPreview}>
                    {item.thinkingContent || 'O modelo está pensando…'}
                  </Text>
                )}
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={item.showThinking ? 'Recolher thinking' : 'Ver thinking completo'}
                  style={styles.thinkingExpandButton}
                  onPress={() => bridge.toggleThinking(item.id)}>
                  <Text style={styles.thinkingArrow}>{item.showThinking ? '⌃' : '⌄'}</Text>
                </Pressable>
              </View>
            </View>
          )}
          {!!item.content && <ChatMarkdown content={item.content} />}
          {item.isStreaming && !item.content && !item.thinkingContent && (
            <ActivityIndicator size="small" color="#FFFFFF" />
          )}
        </View>
      </View>
    );
  };

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
        <StatusBar barStyle="light-content" />
        <KeyboardAvoidingView
          style={styles.screen}
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
          <View style={styles.topControls}>
            <View style={styles.topControlsSpacer} />
            <Pressable
              accessibilityLabel="Criar novo chat"
              style={styles.topIconButton}
              onPress={() => {
                setDraft('');
                setNotice('');
                bridge.clearChat();
              }}>
              <Text style={styles.topIconText}>＋</Text>
            </Pressable>
            <Pressable
              accessibilityLabel="Configurações"
              style={styles.topIconButton}
              onPress={() => setSettingsOpen(true)}>
              <Text style={styles.topIconText}>⚙</Text>
            </Pressable>
          </View>

          {!!notice && (
            <Pressable style={styles.notice} onPress={() => setNotice('')}>
              <Text style={styles.noticeText}>{notice}  ×</Text>
            </Pressable>
          )}

          {state.importing && (
            <View style={styles.importingRow}>
              <ActivityIndicator size="small" color="#FFFFFF" />
              <Text style={styles.importingText}>Importando {state.importName}...</Text>
            </View>
          )}

          {!modelReady && state.messages.length === 0 ? (
            <View style={styles.emptyState}>
              <Text style={styles.heroTitle}>
                {state.modelStatus === 'error' ? 'Não foi possível carregar o modelo' : 'Comece uma conversa'}
              </Text>
              <Text style={styles.heroDescription}>
                Selecione um modelo GGUF para começar.
              </Text>
              <Pressable style={styles.primaryButton} onPress={() => bridge.pickModel()}>
                <Text style={styles.primaryButtonText}>Selecionar modelo</Text>
              </Pressable>
              {state.modelStatus === 'error' && (
                <Text style={styles.errorText}>{state.modelError}</Text>
              )}
            </View>
          ) : (
            <FlatList
              ref={listRef}
              data={state.messages}
              renderItem={renderMessage}
              keyExtractor={(item) => String(item.id)}
              style={styles.messageList}
              contentContainerStyle={styles.messageContent}
              keyboardShouldPersistTaps="handled"
              onContentSizeChange={() => listRef.current?.scrollToEnd({animated: true})}
              ListEmptyComponent={
                <View style={styles.chatEmpty}>
                  <Text style={styles.chatEmptyTitle}>Como posso ajudar?</Text>
                  <Text style={styles.chatEmptyText}>Escreva uma mensagem abaixo.</Text>
                </View>
              }
            />
          )}

          <View style={styles.composerArea}>
            <View style={styles.composer}>
              <Pressable
                accessibilityLabel="Configuração de thinking"
                style={styles.brainModeButton}
                onPress={() => setThinkingModeOpen(true)}>
                <Text style={styles.brainModeIcon}>🧠</Text>
                <Text style={styles.brainModeArrow}>⌄</Text>
              </Pressable>
              <View style={styles.inputWrap}>
                {draft.length === 0 && !inputFocused && (
                  <View pointerEvents="none" style={styles.placeholderLayer}>
                    <Text style={styles.placeholderText}>
                      {'Ask LLM...'.split('').map((character, index, characters) => {
                        const peak = index / (characters.length - 1);
                        const inputRange = index === 0 ? [0, 0.001, 1]
                          : index === characters.length - 1 ? [0, 0.999, 1]
                          : [0, peak, 1];
                        const colors = index === 0 ? ['#F4F4F4', '#F4F4F4', '#777777']
                          : index === characters.length - 1 ? ['#777777', '#F4F4F4', '#F4F4F4']
                          : ['#777777', '#F4F4F4', '#777777'];
                        return (
                          <Animated.Text
                            key={index}
                            style={{color: shimmer.interpolate({inputRange, outputRange: colors, extrapolate: 'clamp'})}}>
                            {character}
                          </Animated.Text>
                        );
                      })}
                    </Text>
                  </View>
                )}
                <TextInput
                  style={styles.input}
                  value={draft}
                  onChangeText={setDraft}
                  placeholder=""
                  multiline
                  maxLength={12000}
                  editable={modelReady && (!state.isGenerating || stopRequested)}
                  onFocus={() => setInputFocused(true)}
                  onBlur={() => setInputFocused(false)}
                  onSubmitEditing={send}
                  returnKeyType="send"
                />
              </View>
              <Pressable
                accessibilityLabel={generating ? 'Parar geração' : 'Enviar mensagem'}
                style={[
                  styles.sendButton,
                  (!modelReady || (!generating && !draft.trim())) && styles.buttonDisabled,
                ]}
                onPress={() => {
                  if (generating) {
                    setStopRequested(true);
                    bridge.stopGeneration();
                  } else {
                    send();
                  }
                }}
                disabled={!generating && (!modelReady || !draft.trim())}>
                <Text style={styles.sendButtonText}>{generating ? '■' : '↑'}</Text>
              </Pressable>
            </View>
          </View>
        </KeyboardAvoidingView>

        <ThinkingModeModal
          visible={thinkingModeOpen}
          onClose={() => setThinkingModeOpen(false)}
        />
        <SettingsModal
          visible={settingsOpen}
          current={state.settings}
          stats={state.stats}
          onClose={() => setSettingsOpen(false)}
          onApply={(next) => {
            bridge.applySettings(next);
            setSettingsOpen(false);
          }}
          onUnload={() => {
            bridge.unloadModel();
            setSettingsOpen(false);
          }}
        />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function ThinkingModeModal({visible, onClose}: {visible: boolean; onClose: () => void}) {
  const levels = ['Off', 'Baixo', 'Médio', 'Alto', 'Máx'];
  return (
    <Modal visible={visible} animationType="fade" transparent onRequestClose={onClose}>
      <Pressable style={styles.modeScrim} onPress={onClose}>
        <Pressable style={styles.modeCard} onPress={() => {}}>
          <View style={styles.modalHeader}>
            <Text style={styles.modalTitle}>Thinking</Text>
            <Pressable onPress={onClose} style={styles.iconButton}>
              <Text style={styles.iconButtonText}>×</Text>
            </Pressable>
          </View>
          <Text style={styles.settingNote}>
            Modo atual: automático, definido pelo template GGUF. Esta ponte nativa ainda não expõe um orçamento de raciocínio; os níveis abaixo ficam indisponíveis para não fingir que alteram o modelo.
          </Text>
          <View style={styles.modeList}>
            <View style={[styles.modeOption, styles.modeOptionSelected]}>
              <Text style={styles.modeOptionText}>Automático</Text>
              <Text style={styles.modeOptionStatus}>Ativo</Text>
            </View>
            {levels.map(level => (
              <View key={level} style={[styles.modeOption, styles.modeOptionDisabled]}>
                <Text style={styles.modeOptionTextDisabled}>{level}</Text>
                <Text style={styles.modeOptionStatus}>Indisponível</Text>
              </View>
            ))}
          </View>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

function SettingsModal({
  visible,
  current,
  stats,
  onClose,
  onApply,
  onUnload,
}: {
  visible: boolean;
  current: InferenceSettings;
  stats: GenerationStats;
  onClose: () => void;
  onApply: (settings: InferenceSettings) => void;
  onUnload: () => void;
}) {
  const [draft, setDraft] = useState<InferenceSettings>(current);

  useEffect(() => {
    if (visible) {
      setDraft(current);
    }
  }, [visible]);

  const cycleKv = (key: 'kvTypeK' | 'kvTypeV') => {
    const index = KV_OPTIONS.findIndex((entry) => entry.code === draft[key]);
    const next = KV_OPTIONS[((index + 1) % KV_OPTIONS.length)] || KV_OPTIONS[0];
    setDraft({...draft, [key]: next.code});
  };

  const toggle = (key: 'flashAttn' | 'useMmap' | 'useMlock' | 'reuseCache') => {
    setDraft({...draft, [key]: !draft[key]});
  };

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.modalScrim}>
        <View style={styles.modalCard}>
          <View style={styles.modalHeader}>
            <View>
              <Text style={styles.modalTitle}>Desempenho</Text>
              <Text style={styles.modalSubtitle}>Parametros do motor local</Text>
            </View>
            <Pressable onPress={onClose} style={styles.iconButton}>
              <Text style={styles.iconButtonText}>×</Text>
            </Pressable>
          </View>
          <ScrollView contentContainerStyle={styles.settingsContent}>
            <Text style={styles.settingLabel}>Contexto</Text>
            <View style={styles.choiceRow}>
              {[2048, 4096, 8192, 16384, 32768].map((size) => (
                <Pressable
                  key={size}
                  onPress={() => setDraft({...draft, nCtx: size})}
                  style={[styles.choice, draft.nCtx === size && styles.choiceSelected]}>
                  <Text style={[styles.choiceText, draft.nCtx === size && styles.choiceTextSelected]}>{size}</Text>
                </Pressable>
              ))}
            </View>

            <Text style={styles.settingLabel}>Threads de geracao</Text>
            <View style={styles.stepper}>
              <Pressable style={styles.choice} onPress={() => setDraft({...draft, nThreads: Math.max(1, draft.nThreads - 1)})}>
                <Text style={styles.choiceText}>−</Text>
              </Pressable>
              <Text style={styles.stepperValue}>{draft.nThreads}</Text>
              <Pressable style={styles.choice} onPress={() => setDraft({...draft, nThreads: Math.min(32, draft.nThreads + 1)})}>
                <Text style={styles.choiceText}>+</Text>
              </Pressable>
            </View>

            <Text style={styles.settingLabel}>Diagnóstico da última geração</Text>
            {stats ? (
              <View style={styles.statsCard}>
                <View style={styles.statsMainRow}>
                  <Text style={styles.statsValue}>{stats.genTokensPerSecond.toFixed(1)} tok/s</Text>
                  <Text style={styles.statsCaption}>velocidade medida no motor nativo</Text>
                </View>
                <View style={styles.statsGrid}>
                  <View style={styles.statsCell}>
                    <Text style={styles.statsCellValue}>{stats.generatedTokens}</Text>
                    <Text style={styles.statsCaption}>tokens gerados</Text>
                  </View>
                  <View style={styles.statsCell}>
                    <Text style={styles.statsCellValue}>{(stats.generationMs / 1000).toFixed(2)} s</Text>
                    <Text style={styles.statsCaption}>tempo de geração</Text>
                  </View>
                  <View style={styles.statsCell}>
                    <Text style={styles.statsCellValue}>{(stats.promptMs / 1000).toFixed(2)} s</Text>
                    <Text style={styles.statsCaption}>processamento do prompt</Text>
                  </View>
                  <View style={styles.statsCell}>
                    <Text style={styles.statsCellValue}>{stats.reusedTokens}/{stats.promptTokens + stats.reusedTokens}</Text>
                    <Text style={styles.statsCaption}>tokens reaproveitados</Text>
                  </View>
                </View>
              </View>
            ) : (
              <Text style={styles.settingNote}>Envie uma mensagem para registrar as métricas reais do motor.</Text>
            )}
            <Text style={styles.settingNote}>
              A taxa tok/s é calculada pelo llama.cpp, não pela frequência de atualização da tela. Se a taxa nativa estiver alta mas a saída visual parecer atrasada, o gargalo está no caminho de atualização da interface.
            </Text>
            <Text style={styles.settingLabel}>KV cache (toque para alternar)</Text>
            <View style={styles.choiceRow}>
              <Pressable style={styles.choice} onPress={() => cycleKv('kvTypeK')}>
                <Text style={styles.choiceText}>K: {KV_OPTIONS.find((item) => item.code === draft.kvTypeK)?.label || 'F16'}</Text>
              </Pressable>
              <Pressable style={styles.choice} onPress={() => cycleKv('kvTypeV')}>
                <Text style={styles.choiceText}>V: {KV_OPTIONS.find((item) => item.code === draft.kvTypeV)?.label || 'F16'}</Text>
              </Pressable>
            </View>

            <SettingToggle label="Flash attention" value={draft.flashAttn} onPress={() => toggle('flashAttn')} />
            <SettingToggle label="Usar mmap" value={draft.useMmap} onPress={() => toggle('useMmap')} />
            <SettingToggle label="Bloquear memoria (mlock)" value={draft.useMlock} onPress={() => toggle('useMlock')} />
            <SettingToggle label="Reutilizar cache entre mensagens" value={draft.reuseCache} onPress={() => toggle('reuseCache')} />
            <Text style={styles.settingNote}>
              KV V quantizado ativa Flash Attention automaticamente. Contextos maiores consomem mais RAM; em CPU, KV quantizado/Flash Attention pode reduzir memória sem necessariamente aumentar velocidade. Alterar estas opções recarrega o modelo.
            </Text>
          </ScrollView>
          <View style={styles.modalActions}>
            <Pressable style={styles.secondaryButton} onPress={onUnload}>
              <Text style={styles.secondaryButtonText}>Descarregar modelo</Text>
            </Pressable>
            <Pressable style={styles.primaryButtonCompact} onPress={() => onApply(draft)}>
              <Text style={styles.primaryButtonText}>Aplicar</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

function SettingToggle({label, value, onPress}: {label: string; value: boolean; onPress: () => void}) {
  return (
    <Pressable style={styles.toggleRow} onPress={onPress}>
      <Text style={styles.toggleLabel}>{label}</Text>
      <View style={[styles.toggle, value && styles.toggleOn]}>
        <View style={[styles.toggleThumb, value && styles.toggleThumbOn]} />
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  safeArea: {flex: 1, backgroundColor: '#000000'},
  screen: {flex: 1, backgroundColor: '#000000'},
  topControls: {height: 44, paddingHorizontal: 14, flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: '#000000'},
  topControlsSpacer: {flex: 1},
  topIconButton: {width: 38, height: 38, alignItems: 'center', justifyContent: 'center', borderRadius: 19},
  topIconText: {fontSize: 23, color: '#FFFFFF', fontWeight: '400'},
  notice: {marginHorizontal: 14, marginTop: 8, padding: 10, borderRadius: 10, backgroundColor: '#252525'},
  noticeText: {fontSize: 12, color: '#FFFFFF'},
  importingRow: {paddingHorizontal: 18, paddingTop: 10, flexDirection: 'row', alignItems: 'center', gap: 8},
  importingText: {fontSize: 12, color: '#D4D4D4'},
  emptyState: {flex: 1, paddingHorizontal: 28, alignItems: 'center', justifyContent: 'center'},
  heroTitle: {fontSize: 21, fontWeight: '600', color: '#FFFFFF', textAlign: 'center', lineHeight: 29},
  heroDescription: {fontSize: 14, lineHeight: 21, color: '#A3A3A3', textAlign: 'center', marginTop: 10, marginBottom: 20},
  primaryButton: {minHeight: 44, paddingHorizontal: 18, borderRadius: 22, backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center'},
  primaryButtonCompact: {minHeight: 44, paddingHorizontal: 22, borderRadius: 22, backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center'},
  primaryButtonText: {fontSize: 13, fontWeight: '700', color: '#000000'},
  errorText: {fontSize: 12, color: '#D4D4D4', marginTop: 14, textAlign: 'center'},
  messageList: {flex: 1},
  messageContent: {paddingHorizontal: 16, paddingVertical: 18, gap: 18},
  messageRow: {flexDirection: 'row', alignItems: 'flex-start'},
  userRow: {justifyContent: 'flex-end'},
  assistantRow: {justifyContent: 'flex-start'},
  messageCard: {maxWidth: '90%', paddingVertical: 10, paddingHorizontal: 14, borderRadius: 20},
  userCard: {backgroundColor: '#2F2F2F', borderTopRightRadius: 6},
  assistantCard: {backgroundColor: '#171717', borderTopLeftRadius: 6},
  messageText: {fontSize: 15, lineHeight: 23, color: '#F5F5F5'},
  thinkingSection: {marginBottom: 12, gap: 5, width: '100%'},
  thinkingHeader: {flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', gap: 6, paddingVertical: 3},
  thinkingBrain: {fontSize: 13, color: '#D4D4D4'},
  thinkingLabel: {fontSize: 12, fontWeight: '600', color: '#D4D4D4', letterSpacing: 0.2},
  thinkingArrow: {fontSize: 17, lineHeight: 19, color: '#FFFFFF'},
  thinkingBox: {width: '100%', padding: 10, borderRadius: 13, borderWidth: 1, borderColor: '#444444', backgroundColor: '#101010'},
  thinkingPreview: {fontSize: 12, lineHeight: 18, color: '#BDBDBD'},
  thinkingExpandButton: {alignSelf: 'flex-end', minWidth: 28, height: 24, alignItems: 'center', justifyContent: 'center', marginTop: 3},
  chatEmpty: {paddingTop: 70, alignItems: 'center'},
  chatEmptyTitle: {fontSize: 17, fontWeight: '600', color: '#E5E5E5'},
  chatEmptyText: {fontSize: 13, color: '#A3A3A3', marginTop: 8},
  composerArea: {paddingHorizontal: 12, paddingTop: 8, paddingBottom: 8, backgroundColor: '#000000'},
  composer: {minHeight: 52, maxHeight: 132, flexDirection: 'row', alignItems: 'flex-end', padding: 5, gap: 6, borderRadius: 26, backgroundColor: '#111111', borderColor: '#383838', borderWidth: StyleSheet.hairlineWidth},
  inputWrap: {flex: 1, minHeight: 40, justifyContent: 'center', overflow: 'hidden'},
  input: {flex: 1, maxHeight: 112, paddingHorizontal: 10, paddingVertical: 9, color: '#FFFFFF', fontSize: 15, lineHeight: 21},
  placeholderLayer: {position: 'absolute', left: 10, right: 0, top: 0, bottom: 0, justifyContent: 'center', overflow: 'hidden'},
  placeholderText: {fontSize: 15, lineHeight: 21, color: '#8C8C8C'},
  brainModeButton: {height: 40, minWidth: 36, paddingHorizontal: 4, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 1},
  brainModeIcon: {fontSize: 17, color: '#D4D4D4'},
  brainModeArrow: {fontSize: 14, color: '#A3A3A3'},
  sendButton: {width: 40, height: 40, borderRadius: 20, backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center'},
  buttonDisabled: {opacity: 0.28},
  sendButtonText: {fontSize: 20, fontWeight: '800', color: '#000000'},
  modeScrim: {flex: 1, justifyContent: 'center', padding: 24, backgroundColor: 'rgba(0,0,0,0.72)'},
  modeCard: {padding: 18, borderRadius: 20, backgroundColor: '#111111', borderWidth: 1, borderColor: '#383838'},
  modeList: {gap: 7, marginTop: 15},
  modeOption: {minHeight: 42, borderRadius: 10, paddingHorizontal: 12, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', backgroundColor: '#202020'},
  modeOptionSelected: {borderWidth: 1, borderColor: '#FFFFFF'},
  modeOptionDisabled: {opacity: 0.48},
  modeOptionText: {fontSize: 13, color: '#FFFFFF', fontWeight: '600'},
  modeOptionTextDisabled: {fontSize: 13, color: '#A3A3A3'},
  modeOptionStatus: {fontSize: 11, color: '#A3A3A3'},
  statsCard: {padding: 12, borderRadius: 13, borderWidth: 1, borderColor: '#383838', backgroundColor: '#0B0B0B', gap: 12},
  statsMainRow: {gap: 4},
  statsValue: {fontSize: 24, fontWeight: '700', color: '#FFFFFF'},
  statsCaption: {fontSize: 10, lineHeight: 15, color: '#A3A3A3'},
  statsGrid: {flexDirection: 'row', flexWrap: 'wrap', gap: 12},
  statsCell: {width: '46%', gap: 3},
  statsCellValue: {fontSize: 14, fontWeight: '600', color: '#E5E5E5'},
  modalScrim: {flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(0,0,0,0.72)'},
  modalCard: {maxHeight: '92%', paddingTop: 20, paddingHorizontal: 18, paddingBottom: 18, backgroundColor: '#111111', borderTopLeftRadius: 24, borderTopRightRadius: 24, borderWidth: 1, borderColor: '#383838'},
  modalHeader: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14},
  modalTitle: {fontSize: 21, fontWeight: '700', color: '#FFFFFF'},
  modalSubtitle: {fontSize: 12, color: '#A3A3A3', marginTop: 4},
  iconButton: {width: 38, height: 38, borderRadius: 19, backgroundColor: '#222222', alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: '#404040'},
  iconButtonText: {fontSize: 19, color: '#FFFFFF'},
  settingsContent: {paddingBottom: 12, gap: 10},
  settingLabel: {fontSize: 12, fontWeight: '700', color: '#D4D4D4', marginTop: 10},
  choiceRow: {flexDirection: 'row', alignItems: 'center', gap: 9, flexWrap: 'wrap'},
  choice: {minHeight: 40, minWidth: 58, paddingHorizontal: 12, borderRadius: 11, borderWidth: 1, borderColor: '#404040', backgroundColor: '#222222', alignItems: 'center', justifyContent: 'center'},
  choiceSelected: {borderColor: '#FFFFFF', backgroundColor: '#3A3A3A'},
  choiceText: {fontSize: 12, fontWeight: '600', color: '#C4C4C4'},
  choiceTextSelected: {color: '#FFFFFF'},
  stepper: {flexDirection: 'row', alignItems: 'center', gap: 20},
  stepperValue: {fontSize: 16, fontWeight: '700', color: '#FFFFFF', minWidth: 24, textAlign: 'center'},
  toggleRow: {minHeight: 45, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#383838'},
  toggleLabel: {fontSize: 13, color: '#E5E5E5', flex: 1, paddingRight: 10},
  toggle: {width: 43, height: 25, borderRadius: 14, backgroundColor: '#3A3A3A', justifyContent: 'center', padding: 3},
  toggleOn: {backgroundColor: '#FFFFFF'},
  toggleThumb: {width: 19, height: 19, borderRadius: 10, backgroundColor: '#BDBDBD'},
  toggleThumbOn: {alignSelf: 'flex-end', backgroundColor: '#000000'},
  settingNote: {fontSize: 11, lineHeight: 17, color: '#A3A3A3', marginTop: 8},
  modalActions: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 10, paddingTop: 10},
  secondaryButton: {minHeight: 44, paddingHorizontal: 12, borderRadius: 22, borderWidth: 1, borderColor: '#555555', alignItems: 'center', justifyContent: 'center'},
  secondaryButtonText: {fontSize: 11, fontWeight: '600', color: '#E5E5E5'},
});
