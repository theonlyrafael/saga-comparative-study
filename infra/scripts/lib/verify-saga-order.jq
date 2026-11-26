# Confirmation replies can arrive in either order. Every other transition is causal.
def path_matches:
    if $expected == "LIQUIDADA" then
        $steps[:3] == ["SaldoReservado","AtivoReservado","LiquidacaoConcluida"]
        and ($steps[3:5] | sort) == ["AtivoConfirmado","SaldoConfirmado"]
        and $steps[5:] == ["OrdemLiquidada"]
    elif $expected == "ATIVO_INSUFICIENTE" then
        $steps == ["SaldoReservado","AtivoRecusado","ReservaSaldoDesfeita","OrdemRejeitada"]
    elif $expected == "SALDO_INSUFICIENTE" then
        $steps == ["SaldoRecusado","OrdemRejeitada"]
    elif $expected == "LIQUIDACAO_NEGADA" then
        $steps == ["SaldoReservado","AtivoReservado","LiquidacaoFalhou","ReservaAtivoDesfeita",
                   "ReservaSaldoDesfeita","OrdemRejeitada"]
    else false end;

def resources_match:
    ($cash == null or ($cash.buyer == $result.clienteId and $cash.amount == $result.valorTotal))
    and ($asset == null or ($asset.buyer == $result.clienteId and $asset.asset == $result.ativoId
                            and $asset.quantity == $result.quantidade));

def outcome_matches:
    if $expected == "LIQUIDADA" then
        $result.estado == "LIQUIDADA" and $result.motivo == null
        and $cash.status == "CONFIRMED" and $asset.status == "CONFIRMED"
        and $settlement.status == "CONFIRMED" and $settlement.id == $result.liquidacaoId
        and $settlement.cash == $cash.id and $settlement.custody == $asset.id
        and $settlement.amount == $result.valorTotal and $settlement.catalog == $result.versaoCatalogo
        and $settlement.buyer == $asset.buyer and $settlement.asset == $asset.asset
        and $settlement.quantity == $asset.quantity
    else
        $result.estado == "REJEITADA" and $result.motivo == $expected
        and $result.liquidacaoId == null and $settlement == null
        and (if $expected == "SALDO_INSUFICIENTE" then $cash == null else $cash.status == "RELEASED" end)
        and (if $expected == "LIQUIDACAO_NEGADA" then $asset.status == "RELEASED" else $asset == null end)
    end;

{orderId:$order, sagaId:$saga, orderState:$result.estado, sagaState:$state, reason:$result.motivo,
 buyer:$result.clienteId, asset:$result.ativoId, cashReservation:$cash.status, assetReservation:$asset.status,
 settlementId:$settlement.id, responses:$steps} |
if $result.orderId == $order and $result.sagaId == $saga and $result.versaoCatalogo == "demo-v1"
   and resources_match and outcome_matches and path_matches then .
else error("order invariant failed for "+$expected+": "+tostring) end
