package lib

import (
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/vm/runtime"
	"libevm/types"
)

type EvmParams struct {
	HandleParams
	Config  types.SerializableConfig `json:"config"`
	Address *common.Address          `json:"address"`
	Input   []byte                   `json:"input"`
}

type EvmResult struct {
	ReturnData  []byte
	Address     *common.Address
	LeftOverGas uint64
	EvmError    string
}

func (s *Service) EvmExecute(params EvmParams) (error, *EvmResult) {
	err, statedb := s.getState(params.Handle)
	if err != nil {
		return err, nil
	}
	cfg := params.Config.GetConfig()
	cfg.State = statedb
	var (
		result *EvmResult
		evmErr error
	)
	if params.Address == nil {
		_, *result.Address, result.LeftOverGas, evmErr = runtime.Create(params.Input, cfg)
	} else {
		result.ReturnData, result.LeftOverGas, evmErr = runtime.Call(*params.Address, params.Input, cfg)
	}
	if evmErr != nil {
		result.EvmError = evmErr.Error()
	}
	return nil, result
}
